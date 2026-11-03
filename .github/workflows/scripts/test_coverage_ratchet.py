"""Tests for Scripts/coverage-ratchet.py (stdlib only, runs in the unit-tests CI job)."""
import importlib.util
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "Scripts" / "coverage-ratchet.py"


def load_module():
    spec = importlib.util.spec_from_file_location("coverage_ratchet", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CoverageRatchetTest(unittest.TestCase):
    def setUp(self):
        self.mod = load_module()
        self._cwd = os.getcwd()
        self.tmp = tempfile.TemporaryDirectory()
        os.chdir(self.tmp.name)
        os.makedirs("target")

    def tearDown(self):
        os.chdir(self._cwd)
        self.tmp.cleanup()

    def _summary(self, pct):
        Path("target/coverage-summary.json").write_text(json.dumps({"line_pct": pct}), encoding="utf-8")

    def _baseline(self, text):
        Path("b.properties").write_text(text, encoding="utf-8")

    def _run(self, *args):
        out = StringIO()
        with redirect_stdout(out):
            rc = self.mod.main(["--baseline", "b.properties", *args])
        return rc, out.getvalue()

    def test_passes_within_allowed_drop(self):
        self._summary(74.5)
        self._baseline("core.line.pct=75\ncore.max.drop.pct=1.0\n")
        self.assertEqual(self._run()[0], 0)

    def test_fails_when_dropped_too_far(self):
        self._summary(73.0)
        self._baseline("core.line.pct=75\ncore.max.drop.pct=1.0\n")
        rc, out = self._run()
        self.assertEqual(rc, 1)
        self.assertIn("FAIL", out)

    def test_empty_baseline_passes_and_update_records_value(self):
        self._summary(66.25)
        self._baseline("# comment\ncore.line.pct=\ncore.max.drop.pct=2\n")
        self.assertEqual(self._run()[0], 0)
        self.assertEqual(self._run("--update")[0], 0)
        text = Path("b.properties").read_text(encoding="utf-8")
        self.assertIn("core.line.pct=66.25", text)
        self.assertIn("# comment", text)
        self.assertIn("core.max.drop.pct=2", text)

    def test_update_only_raises_never_lowers(self):
        self._baseline("core.line.pct=80\n")
        self._summary(79.5)  # inside the allowed drop, so OK but must not lower the baseline
        self.assertEqual(self._run("--update")[0], 0)
        self.assertIn("core.line.pct=80", Path("b.properties").read_text(encoding="utf-8"))
        self._summary(82.0)
        self.assertEqual(self._run("--update")[0], 0)
        self.assertIn("core.line.pct=82.00", Path("b.properties").read_text(encoding="utf-8"))

    def test_no_coverage_data_is_not_a_failure(self):
        self._baseline("core.line.pct=80\n")
        self.assertEqual(self._run()[0], 0)


if __name__ == "__main__":
    unittest.main()
