"""Exact per-test history: case_history storage, exact compare / test history, and the end-to-end recording."""
import json
import os
import tempfile
import unittest

import case_history as ch
import results
import results_history as rh
import run_insights as ri


def write_report(repo, cls, cases):
    """cases: [(name, seconds, 'pass'|'fail'|'skip')]"""
    d = os.path.join(repo, "target", "surefire-reports")
    os.makedirs(d, exist_ok=True)
    body = ""
    for name, secs, st in cases:
        inner = {"pass": "", "fail": '<failure message="boom" type="java.lang.AssertionError">boom</failure>', "skip": "<skipped/>"}[st]
        body += f'<testcase name="{name}" classname="{cls}" time="{secs}">{inner}</testcase>'
    n = len(cases)
    fails = sum(1 for c in cases if c[2] == "fail")
    skips = sum(1 for c in cases if c[2] == "skip")
    with open(os.path.join(d, f"TEST-{cls}.xml"), "w") as f:
        f.write(f'<?xml version="1.0"?><testsuite name="{cls}" tests="{n}" failures="{fails}" errors="0" skipped="{skips}" time="9">{body}</testsuite>')


class DataDirCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["DASHBOARD_DATA_DIR"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("DASHBOARD_DATA_DIR", None))


class CaseHistoryTests(DataDirCase):
    def test_record_is_idempotent_per_run_and_capped(self):
        self.assertTrue(ch.record(1, [["A.x", 0, 1.0]]))
        self.assertFalse(ch.record(1, [["A.x", 1, 9.0]]), "a run already stored is left alone")
        for t in range(2, ch.MAX_RUNS + 6):
            ch.record(t, [["A.x", 0, 1.0]])
        runs = ch.by_run()
        self.assertEqual(len(runs), ch.MAX_RUNS)
        self.assertNotIn(1, runs)

    def test_invalid_rows_are_dropped(self):
        ch.record(5, [["ok", 0, 1], ["bad-code", 7, 1], ["neg", 0, -1], [3, 0, 1], ["short"], ["bool", True, 1], ["x" * 400, 1, 2]])
        c = ch.by_run()[5]
        self.assertEqual(sorted(k[:3] for k in c), ["ok", "xxx"])
        self.assertEqual(len(max(c, key=len)), 300)

    def test_damaged_file_yields_nothing_not_a_crash(self):
        for junk in ("{not json", json.dumps({"runs": "x"}), json.dumps([1, 2]), json.dumps({"runs": [{"t": True, "c": []}, {"t": 1, "c": "no"}]})):
            with open(ch.path(), "w") as f:
                f.write(junk)
            self.assertEqual(ch.by_run(), {})
        self.assertTrue(ch.record(9, [["A.x", 0, 1.0]]), "and it recovers on the next save")

    @unittest.skipIf(os.name == "nt", "POSIX modes")
    def test_file_is_private(self):
        ch.record(1, [["A.x", 0, 1.0]])
        self.assertEqual(os.stat(ch.path()).st_mode & 0o777, 0o600)


class ExactCompareTests(unittest.TestCase):
    H = [{"t": 1, "sig": "a", "rate": 50, "total": 5, "failed": 2, "dur": 10, "fails": ["A.fixed", "A.gone", "A.skipnow", "A.still"]},
         {"t": 2, "sig": "b", "rate": 50, "total": 5, "failed": 3, "dur": 20, "fails": ["A.still", "A.newtest", "A.regress"], "msgs": {"A.regress": "NPE"}}]
    C = {1: {"A.fixed": (1, 1.0), "A.gone": (1, 1.0), "A.skipnow": (1, 1.0), "A.still": (1, 1.0), "A.regress": (0, 1.0), "A.slow": (0, 2.0)},
         2: {"A.fixed": (0, 1.0), "A.skipnow": (2, 0.0), "A.still": (1, 1.0), "A.regress": (1, 1.0), "A.newtest": (1, 1.0), "A.slow": (0, 9.0)}}

    def test_exact_buckets(self):
        r = ri.compare(self.H, 1, 2, self.C)
        self.assertTrue(r["exact"])
        self.assertEqual(r["fixed"], ["A.fixed"], "only a test that now PASSES is fixed")
        self.assertEqual(sorted(r["no_longer_run"]), ["A.gone", "A.skipnow"], "absent or skipped is not 'fixed'")
        self.assertEqual(r["still_failing"], ["A.still"])
        new = {x["test"]: x for x in r["new_failures"]}
        self.assertEqual(sorted(new), ["A.newtest", "A.regress"])
        self.assertEqual(new["A.regress"]["was"], "passing")
        self.assertEqual(new["A.regress"]["message"], "NPE")
        self.assertEqual(new["A.newtest"]["was"], "not in the baseline run")
        self.assertEqual(r["counts"], {"new_failures": 2, "fixed": 1, "still_failing": 1, "no_longer_run": 2})

    def test_times_are_compared_for_every_test_not_only_the_slowest(self):
        r = ri.compare(self.H, 1, 2, self.C)
        self.assertEqual([x["test"] for x in r["slower"]], ["A.slow"])
        self.assertEqual(r["slower"][0]["delta"], 7.0)

    def test_falls_back_to_approximate_when_one_side_has_no_cases(self):
        r = ri.compare(self.H, 1, 2, {2: self.C[2]})
        self.assertFalse(r["exact"])
        self.assertTrue(r["per_test"])
        self.assertIn("A.fixed", r["fixed"])      # the approximate rule: absent from B's failures
        self.assertEqual(r["no_longer_run"], [])


class ExactTestHistoryTests(unittest.TestCase):
    def test_statuses_skips_and_absent_runs(self):
        H = [{"t": t, "sig": str(t), "rate": 1, "fails": []} for t in (1, 2, 3, 4)]
        H[0]["fails"] = ["A.x"]
        C = {2: {"A.x": (0, 1.5)}, 3: {"A.x": (2, 0.0)}, 4: {"A.y": (0, 1.0)}}
        r = ri.test_history(H, "A.x", C)
        self.assertEqual([x["status"] for x in r["runs"]], ["fail", "pass", "skip", "absent"])
        self.assertEqual(r["exact_runs"], 3)
        self.assertEqual((r["fails"], r["avg_seconds"]), (1, 1.5))
        self.assertEqual(r["kind"], "recovered", "skip/absent are neither passes nor failures for the verdict")


class EndToEndTests(DataDirCase):
    def test_snapshot_records_every_test_once_and_never_sends_them_to_the_browser(self):
        repo = tempfile.mkdtemp()
        write_report(repo, "com.acme.CartTest", [("a", 1.0, "pass"), ("b", 2.0, "fail"), ("c", 0.0, "skip")])
        snap = results.full_snapshot(repo)
        self.assertNotIn("cases", snap["details"], "per-test data stays server-side")
        self.assertNotIn("cases", json.dumps(snap["history"]))
        runs = ch.by_run()
        self.assertEqual(len(runs), 1)
        cases = next(iter(runs.values()))
        self.assertEqual({k: v[0] for k, v in cases.items()}, {"com.acme.CartTest.a": 0, "com.acme.CartTest.b": 1, "com.acme.CartTest.c": 2})
        results.full_snapshot(repo)
        self.assertEqual(len(ch.by_run()), 1, "polling the same run again stores nothing new")
        self.assertEqual(len(rh.read()), 1)

    def test_a_new_run_is_a_new_entry_and_the_two_can_be_compared_exactly(self):
        repo = tempfile.mkdtemp()
        write_report(repo, "com.acme.CartTest", [("a", 1.0, "pass"), ("b", 2.0, "fail")])
        results.full_snapshot(repo)
        write_report(repo, "com.acme.CartTest", [("a", 1.0, "fail"), ("b", 2.0, "pass")])
        p = os.path.join(repo, "target", "surefire-reports", "TEST-com.acme.CartTest.xml")
        os.utime(p, (os.path.getmtime(p) + 100, os.path.getmtime(p) + 100))
        results.full_snapshot(repo)
        hist = rh.read()
        self.assertEqual(len(hist), 2)
        r = ri.compare(hist, hist[0]["t"], hist[1]["t"], ch.by_run())
        self.assertTrue(r["exact"])
        self.assertEqual(r["fixed"], ["com.acme.CartTest.b"])
        self.assertEqual([x["test"] for x in r["new_failures"]], ["com.acme.CartTest.a"])


if __name__ == "__main__":
    unittest.main()
