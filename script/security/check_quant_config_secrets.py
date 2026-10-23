#!/usr/bin/env python3
"""Guard committed quant configuration against plaintext third-party credentials."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
PROFILES = (
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


def is_external(value: str) -> bool:
    return value.startswith("${") and value.endswith("}")


def main() -> int:
    violations = []
    for path in PROFILES:
        content = path.read_text(encoding="utf-8")
        mail = section(content, "--- # mail ")
        for key, expected in {
            "enabled": "${AIQUANT_MAIL_ENABLED:false}",
            "from": "${AIQUANT_MAIL_FROM:}",
            "user": "${AIQUANT_MAIL_USER:}",
            "pass": "${AIQUANT_MAIL_PASSWORD:}",
        }.items():
            if property_value(mail, key) != expected:
                violations.append(f"{path.name}: mail.{key} must use the approved external value")

        for marker, fields in (
            ("--- # snail-job 配置", ("token",)),
            ("--- # snail-ai 配置", ("token",)),
            ("--- # sms ", ("access-key-id", "access-key-secret", "signature", "sdk-app-id")),
        ):
            block = section(content, marker)
            for field in fields:
                for match in re.finditer(rf"(?m)^\s+{re.escape(field)}:\s*(\S.*)?$", block):
                    value = (match.group(1) or "").strip()
                    if value and not is_external(value):
                        violations.append(f"{path.name}: {marker.strip('- #')} {field} must be external")

        justauth = section(content, "--- # 三方授权")
        justauth_start = content.find("--- # 三方授权")
        for match in re.finditer(r"(?m)^\s*client-secret:\s*(\S.*)?$", justauth):
            value = (match.group(1) or "").strip()
            if value and not is_external(value):
                line = content[:justauth_start + match.start()].count("\n") + 1
                violations.append(f"{path.name}:{line}: justauth client-secret must be external")

        for key in ("secret-key", "password"):
            for match in re.finditer(rf"(?m)^\s+{re.escape(key)}:\s*(\S.*)?$", content):
                value = (match.group(1) or "").strip()
                if value and not (value.startswith("${") or value.startswith("@")):
                    violations.append(f"{path.name}:{content[:match.start()].count(chr(10))+1}: literal {key} value")

    common = COMMON.read_text(encoding="utf-8")
    jwt_values = re.findall(r"(?m)^\s+ jwt-secret-key:\s*(\S.*)?$", common)
    if len(jwt_values) != 1 or (jwt_values[0] or "").strip() != "${AIQUANT_SA_TOKEN_JWT_SECRET_KEY}":
        violations.append(f"{COMMON.name}: sa-token.jwt-secret-key must use its deployment secret")

    api_decrypt = section(common, "# api接口加密")
    private_values = re.findall(r"(?m)^\s+privateKey:\s*(\S.*)?$", api_decrypt)
    if len(private_values) != 1 or (private_values[0] or "").strip() != "${AIQUANT_API_DECRYPT_PRIVATE_KEY}":
        violations.append(f"{COMMON.name}: api-decrypt.privateKey must use its deployment secret")

    mqtt = section(common, "mqtt.client:")
    for field in ("username", "password"):
        expected = f"${{AIQUANT_MQTT_{field.upper()}:}}"
        if property_value(mqtt, field) != expected:
            violations.append(f"{COMMON.name}: mqtt.client.{field} must be external")

    if violations:
        for violation in violations:
            print("CONFIG-SECRET-ERROR:", violation, file=sys.stderr)
        return 1
    print("PASS: profile mail, task-service, SMS and OAuth credentials are externalized")
    return 0


if __name__ == "__main__":
    sys.exit(main())
