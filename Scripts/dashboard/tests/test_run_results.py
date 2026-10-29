import json
import os
import time
import unittest

from tests import _helpers as h
from runner import run_results as rr

PASS_XML = ('<testsuite><testcase classname="com.x.LoginTest" name="ok" time="1.5"/>'
            '<testcase classname="com.x.LoginTest" name="skipme" time="0"><skipped/></testcase></testsuite>')
FAIL_XML = ('<testsuite><testcase classname="com.x.CartTest" name="bad" time="2">'
            '<failure message="expected [3]  but found [4]">stack</failure></testcase>'
            '<testcase classname="com.x.CartTest" name="boom" time="1"><error message="NoSuchElementException">x</error></testcase>'
            '</testsuite>')


class ReadCountsTests(unittest.TestCase):
    def test_counts_failures_and_skips(self):
        with h.tmpdir() as d:
            h.write(os.path.join(d, "TEST-a.xml"), PASS_XML)
            h.write(os.path.join(d, "TEST-b.xml"), FAIL_XML)
            c = rr.read_counts(d)
        self.assertEqual((c["total"], c["passed"], c["failed"], c["skipped"]), (4, 1, 2, 1))
        self.assertEqual(c["failures"][0]["class"], "CartTest")
        self.assertEqual(c["failures"][0]["message"], "expected [3] but found [4]")  # whitespace collapsed
        self.assertAlmostEqual(c["testSeconds"], 4.5)

    def test_stale_files_from_an_earlier_run_are_ignored(self):
        with h.tmpdir() as d:
            f = os.path.join(d, "TEST-old.xml")
            h.write(f, FAIL_XML)
            old = time.time() - 3600
            os.utime(f, (old, old))
            self.assertIsNone(rr.read_counts(d, since=time.time()))
            self.assertEqual(rr.read_counts(d, since=old - 10)["failed"], 2)

    def test_missing_dir_or_garbage_gives_none(self):
        with h.tmpdir() as d:
            self.assertIsNone(rr.read_counts(os.path.join(d, "nope")))
            h.write(os.path.join(d, "TEST-bad.xml"), "<not xml")
            self.assertIsNone(rr.read_counts(d))

    def test_several_dirs_are_combined(self):
        with h.tmpdir() as a, h.tmpdir() as b:
            h.write(os.path.join(a, "TEST-a.xml"), PASS_XML)
            h.write(os.path.join(b, "TEST-b.xml"), FAIL_XML)
            self.assertEqual(rr.read_counts([a, b])["total"], 4)


class DiagnoseTests(unittest.TestCase):
    def test_known_causes(self):
        self.assertIn("could not be found", rr.diagnose("java.net.UnknownHostException: nope.example")[0])
        self.assertIn("Nothing answered", rr.diagnose("Connection refused: localhost:8080")[0])
        self.assertTrue(any("Appium" in x for x in rr.diagnose("Could not start a new session ... 4723")))
        self.assertTrue(any("certificate" in x for x in rr.diagnose("javax.net.ssl.SSLHandshakeException")))

    def test_safe_on_junk_and_capped(self):
        self.assertEqual(rr.diagnose(None), [])
        self.assertEqual(rr.diagnose(""), [])
        noisy = " ".join(["Connection refused", "UnknownHostException", "401 Unauthorized", "404 Not Found",
                          "429", "OutOfMemoryError", "Permission denied"])
        self.assertLessEqual(len(rr.diagnose(noisy)), rr.MAX_HINTS)


class SummarizeTests(unittest.TestCase):
    def _run(self, state, xml=None, test_like=True, log="", code=0, since=None):
        with h.tmpdir() as d:
            sf = os.path.join(d, "sf")
            os.makedirs(sf)
            if xml:
                h.write(os.path.join(sf, "TEST-x.xml"), xml)
            logp = os.path.join(d, "run.log")
            h.write(logp, log)
            return rr.summarize("20260101-000000-abcdef", {"name": "N", "type": "ui"}, state, code, 12.0, ["allure-results"],
                                surefire_dirs=[sf], since=since, log_path=logp, test_like=test_like)

    def test_all_passed(self):
        r = self._run("passed", PASS_XML)
        self.assertEqual(r["verdict"], "passed")
        self.assertIn("All 1 checks passed (1 skipped)", r["headline"])
        self.assertEqual(r["reports"][0]["link"], "/api/runs/20260101-000000-abcdef/files/report/allure-results.zip")

    def test_failed_lists_failures(self):
        r = self._run("failed", FAIL_XML, code=1)
        self.assertEqual(r["verdict"], "failed")
        self.assertEqual(r["headline"], "2 of 2 checks failed in 12 s.")
        self.assertEqual(len(r["failures"]), 2)
        self.assertTrue(r["hints"])

    def test_green_exit_without_results_is_a_warning_not_a_pass(self):
        r = self._run("passed", None)
        self.assertEqual(r["verdict"], "no-tests")
        self.assertIn("no test results", r["headline"])

    def test_housekeeping_runs_are_not_expected_to_have_results(self):
        r = self._run("passed", None, test_like=False)
        self.assertEqual(r["verdict"], "passed")
        self.assertEqual(r["headline"], "Finished successfully in 12 s.")

    def test_failed_before_any_test_uses_the_log(self):
        r = self._run("failed", None, log="... java.net.ConnectException: Connection refused ...", code=1)
        self.assertIn("before any test results", r["headline"])
        self.assertIn("Nothing answered", r["hints"][0])

    def test_hints_also_read_the_failure_messages(self):
        xml = ('<testsuite><testcase classname="a.B" name="x" time="1"><failure message="java.net.ConnectException: Connection refused">t'
               '</failure></testcase></testsuite>')
        r = self._run("failed", xml, code=1, log="BUILD FAILURE")
        self.assertIn("Nothing answered", r["hints"][0])

    def test_timeout_cancelled_error(self):
        self.assertEqual(self._run("timeout")["verdict"], "timeout")
        self.assertIn("Raise 'Stop the run after", self._run("timeout")["hints"][0])
        self.assertEqual(self._run("cancelled")["verdict"], "cancelled")
        self.assertEqual(self._run("error")["verdict"], "error")

    def test_write_read_roundtrip_and_fallback(self):
        with h.tmpdir() as d:
            self.assertIsNone(rr.read(d))
            rr.write(d, {"verdict": "passed"})
            self.assertEqual(rr.read(d), {"verdict": "passed"})
        self.assertEqual(rr.fallback({"id": "x", "state": "running"})["verdict"], "running")
        self.assertEqual(rr.fallback({"id": "x", "state": "failed"})["verdict"], "failed")
        json.dumps(rr.fallback({}))


if __name__ == "__main__":
    unittest.main()
