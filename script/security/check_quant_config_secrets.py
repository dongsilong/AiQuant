#!/usr/bin/env python3
"""Fail the production hardening CI if a known secret field regresses to plaintext.
Only committed configuration is checked; deployment secret rotation is a separate task.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FILES = (
    ROOT / "ruoyi-admin/src/main/resources/application-dev.yml",
    ROOT / "ruoyi-admin/src/main/resources/application-prod.yml",
)
COMMON = ROOT / "ruoyi-admin/src/main/resources/application.yml"


def section(document: str, marker: str) -> str:
    start = document.find(marker)
    if start < 0:
        raise ValueError(f"Missing expected section: {marker}")
    remaining = document[start + len(marker):]
    next_separator = re.search(r"(?m)^---", remaining)
    return remaining[:next_separator.start()] if next_separator else remaining


def property_value(data: str, key: str) -> str:
    match = re.search(rf"(?m)^\s+{re.escape(key)}:\s*(\S.*)?$", data)
    if not match:
        raise ValueError(f"Missing required key: {key}")
    return (match.group(1) or "").strip()


def main() -> int:
    violations = []
    for file in FILES:
        content = file.read_text(encoding="utf-8")
        mail = section(content, "--- # mail ")
        expected = {
            "enabled": "${AIQUANT_MAIL_ENABLED:false}",
            "from": "${AIQUANT_MAIL_FROM:}",
            "user": "${AIQUANT_MAIL_USER:}",
            "pass": "${AIQUANT_MAIL_PASSWORD:}",
        }
        for key, value in expected.items():
            if property_value(mail, key) != value:
                violations.append(f"{file.name}: mail.{key} must use the approved external value")
    common = COMMON.read_text(encoding="utf-8")
    if property_value(common, "jwt-secret-key") != "${AIQUANT_JWT_SECRET_KEY}":
        violations.append(f"{COMMON.name}: jwt-secret-key must be externally provided")
    mqtt = section(common, "mqtt.client:")
    for field in ("username", "password"):
        expected = f"${{AIQUANT_MQTT_{field.upper()}:}}"
        if property_value(mqtt, field) != expected:
            violations.append(f"{COMMON.name}: mqtt.client.{field} must be external")
    # Reject any non-placeholder literal for critical quant secret keys in these profiles.
    for file in FILES:
        content = file.read_text(encoding="utf-8")
        for key in ("secret-key", "password"):
            for match in re.finditer(rf"(?m)^\s+{re.escape(key)}:\s*(\S.*)?$", content):
                val = (match.group(1) or "").strip()
                if val and not (val.startswith("${") or val.startswith("@")):
                    violations.append(f"{file.name}:{content[:match.start()].count(chr(10))+1}: literal {key} value")
    if violations:
        for violation in violations:
            print("CONFIG-SECRET-ERROR:", violation, file=sys.stderr)
        return 1
    print("PASS: critical LIVE application secrets are externally provided")
    return 0


if __name__ == "__main__":
    sys.exit(main())
