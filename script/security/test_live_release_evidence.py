#!/usr/bin/env python3
"""Offline regression checks for LIVE release evidence integrity."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name("check_live_release_evidence.py")
spec = importlib.util.spec_from_file_location("live_release_gate", MODULE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class ReleaseEvidenceTest(unittest.TestCase):
    def test_missing_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "release_commit": "a" * 40,
                "approvals": {"operator": "alice", "independent_reviewer": "bob"},
                "evidence": {}
            }), encoding="utf-8")
            self.assertEqual(len(gate.verify(manifest, root)), len(gate.REQUIRED))

    def test_self_approval_and_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "release_commit": "b" * 40,
                "approvals": {"operator": "alice", "independent_reviewer": "alice"},
                "evidence": {name: {"file": "../missing.json", "sha256": "0" * 64}
                             for name in gate.REQUIRED}
            }), encoding="utf-8")
            errors = gate.verify(manifest, root)
            self.assertTrue(any("different" in e for e in errors))
            self.assertEqual(sum("evidence not found" in e for e in errors), len(gate.REQUIRED))

if __name__ == "__main__":
    unittest.main()
