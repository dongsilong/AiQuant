#!/usr/bin/env python3
"""Fail-closed verification of external LIVE release evidence.

This verifies file presence, approvals and SHA256 integrity, not the truth of
Binance/database observations. Independent human review is still mandatory.
"""
import argparse
import hashlib
import json
from pathlib import Path

REQUIRED = (
    "ci", "schema_backup_restore", "binance_fault_injection",
    "multi_node_fencing", "position_pnl_reconciliation",
    "operator_recovery_approval", "deployment_rollback_monitoring",
)

def verify(path: Path, root: Path) -> list[str]:
    errors = []
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"Cannot read manifest: {exc}"]
    if manifest.get("release_commit") is None or len(str(manifest["release_commit"])) != 40:
        errors.append("release_commit must be a full 40-character Git SHA")
    approvals = manifest.get("approvals", {})
    if not isinstance(approvals, dict) or not approvals.get("operator") or not approvals.get("independent_reviewer"):
        errors.append("Operator and independent reviewer are required")
    elif approvals["operator"] == approvals["independent_reviewer"]:
        errors.append("Operator and reviewer must be different")
    evidence = manifest.get("evidence", {})
    if not isinstance(evidence, dict):
        return errors + ["evidence must be an object"]
    for category in REQUIRED:
        item = evidence.get(category)
        if not isinstance(item, dict):
            errors.append(f"{category}: missing evidence")
            continue
        relative = item.get("file")
        expected = item.get("sha256")
        if not isinstance(relative, str) or not isinstance(expected, str) or len(expected) != 64:
            errors.append(f"{category}: missing relative file path or 64-char SHA256")
            continue
        candidate = (root / relative).resolve()
        if not candidate.is_relative_to(root.resolve()) or not candidate.is_file():
            errors.append(f"{category}: evidence not found inside evidence root")
            continue
        actual = hashlib.sha256(candidate.read_bytes()).hexdigest()
        if actual != expected.lower():
            errors.append(f"{category}: SHA256 mismatch")
    return errors

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--evidence-root", type=Path, required=True)
    args = parser.parse_args()
    failures = verify(args.manifest, args.evidence_root)
    if failures:
        for failure in failures:
            print("BLOCK:", failure)
        raise SystemExit(1)
    print("Evidence manifest integrity PASS; independent acceptance still required")
