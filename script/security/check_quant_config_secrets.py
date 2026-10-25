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
    # Reject private-key material hidden in YAML comments as well as active properties.
    for file in (COMMON, *FILES):
        for line_number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"(?i)private.?key", line) and re.search(
                r"(?i)(?:MIIC|MIIE|MIGH|MIGT|BEGIN.{0,24}PRIVATE KEY|[A-Za-z0-9+/]{120,}={0,2})", line
            ):
                violations.append(f"{file.name}:{line_number}: private-key material must not be committed, including comments")
    common = COMMON.read_text(encoding="utf-8")
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
    # Shared JWT/API decryption keys must not regress to tracked literals.
    jwt = section(common, "sa-token:")
    if property_value(jwt, "jwt-secret-key") != "${AIQUANT_SA_TOKEN_JWT_SECRET:}":
        violations.append(f"{COMMON.name}: sa-token.jwt-secret-key must be external")
    decrypt = section(common, "api-decrypt:")
    if property_value(decrypt, "privateKey") != "${AIQUANT_API_DECRYPT_PRIVATE_KEY:}":
        violations.append(f"{COMMON.name}: api-decrypt.privateKey must be external")

    for file in FILES:
        content = file.read_text(encoding="utf-8")
        transport = section(content, "binance:")
        if property_value(transport, "websocket-transport") != "${QUANT_BINANCE_WEBSOCKET_TRANSPORT:RAW_OKHTTP}":
            violations.append(f"{file.name}: LIVE WebSocket transport must default to the audited RAW_OKHTTP path")
        if file.name == "application-prod.yml":
            datasource = section(content, "datasource:")
            for key, expected in (
                ("url", "${AIQUANT_DATASOURCE_URL:}"),
                ("username", "${AIQUANT_DATASOURCE_USERNAME:}"),
            ):
                if property_value(datasource, key) != expected:
                    violations.append(f"{file.name}: production datasource {key} must be externally configured")
            redis = section(content, "spring.data:")
            for key, expected in (
                ("host", "${AIQUANT_REDIS_HOST:}"),
                ("port", "${AIQUANT_REDIS_PORT:}"),
            ):
                if property_value(redis, key) != expected:
                    violations.append(f"{file.name}: production Redis {key} must be externally configured")
        expected_switches = {
            "  live:": "${QUANT_LIVE_ENABLED:true}",
            "  runtime:": "${QUANT_RUNTIME_ENABLED:true}" if file.name == "application-dev.yml" else "${QUANT_RUNTIME_ENABLED:false}",
        }
        for marker, expected in expected_switches.items():
            block = section(content, marker)
            if property_value(block, "enabled") != expected:
                violations.append(f"{file.name}: {marker.split(':')[0]} enabled must equal {expected}")
        for marker, expected in (
            ("snail-job:", "${AIQUANT_SNAIL_JOB_TOKEN:}"),
            ("snail-ai:", "${AIQUANT_SNAIL_AI_TOKEN:}"),
        ):
            try:
                if property_value(section(content, marker), "token") != expected:
                    violations.append(f"{file.name}: {marker.rstrip(':')} token must be external")
            except ValueError as exc:
                violations.append(f"{file.name}: {exc}")
        auth = section(content, "justauth:")
        for line_number, line in enumerate(auth.splitlines(), 1):
            match = re.match(r"^\s*client-secret:\s*(.*)$", line)
            if match and not re.fullmatch(r"\$\{AIQUANT_JUSTAUTH_[A-Z0-9_]+_CLIENT_SECRET:\}", match.group(1).strip()):
                violations.append(f"{file.name}: justauth client-secret at section line {line_number} must be external")

    if violations:
        for violation in violations:
            print("CONFIG-SECRET-ERROR:", violation, file=sys.stderr)
        return 1
    print("PASS: critical LIVE application secrets are externally provided")
    return 0


if __name__ == "__main__":
    sys.exit(main())
