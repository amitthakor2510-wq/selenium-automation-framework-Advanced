import unittest

import quality_gate as q

LIM = q.parse_limits({})
GOOD = {"total": 100, "passed": 100, "failed": 0, "skipped": 0}


def _hist(*durs):
    return [{"t": i, "rate": 100, "dur": d} for i, d in enumerate(durs)]


def _by_id(verdict):
    return {c["id"]: c for c in verdict["checks"]}


class QualityGate(unittest.TestCase):
    def test_no_run_is_unknown_never_pass(self):
        self.assertEqual(q.evaluate({}, None, [], None, LIM)["status"], "unknown")
        self.assertEqual(q.evaluate({"total": 0}, None, [], None, LIM)["status"], "unknown")

    def test_clean_run_passes(self):
        v = q.evaluate(GOOD, {"line_pct": 70, "threshold_pct": 60}, _hist(10, 10, 10, 10, 10), {"flaky_count": 0}, LIM)
        self.assertEqual(v["status"], "pass")
        self.assertTrue(all(c["status"] in ("pass", "skip") for c in v["checks"]))

    def test_failures_block_the_gate(self):
        v = q.evaluate({"total": 100, "passed": 90, "failed": 10}, None, [], None, LIM)
        self.assertEqual(v["status"], "fail")
        c = _by_id(v)
        self.assertEqual(c["pass_rate"]["status"], "fail")
        self.assertEqual(c["failed"]["status"], "fail")
        self.assertIn("pass rate", v["summary"])

    def test_missing_evidence_is_unknown_not_pass_or_fail(self):
        c = _by_id(q.evaluate(GOOD, {"line_pct": 70, "threshold_pct": 60}, [], None, LIM))
        self.assertEqual(c["slowdown"]["status"], "unknown")
        self.assertEqual(c["flaky"]["status"], "unknown")
        c = _by_id(q.evaluate(GOOD, None, [], None, q.parse_limits({"min_coverage": ["80"]})))
        self.assertEqual(c["coverage"]["status"], "unknown")

    def test_coverage_below_limit_fails(self):
        v = q.evaluate(GOOD, {"line_pct": 55, "threshold_pct": 60}, [], None, LIM)
        self.assertEqual(_by_id(v)["coverage"]["status"], "fail")
        self.assertEqual(v["status"], "fail")

    def test_slowdown_uses_median_of_previous_runs(self):
        slow = _by_id(q.evaluate(GOOD, None, _hist(10, 10, 10, 10, 30), None, LIM))
        self.assertEqual(slow["slowdown"]["status"], "fail")
        self.assertEqual(slow["slowdown"]["actual"], 200.0)
        steady = _by_id(q.evaluate(GOOD, None, _hist(10, 10, 10, 10, 11), None, LIM))
        self.assertEqual(steady["slowdown"]["status"], "pass")

    def test_query_limits_override_defaults_and_prefs(self):
        lim = q.parse_limits({"min_pass": ["80"], "max_failed": ["12"]}, {"alert_pass_pct": 95})
        self.assertEqual(lim["min_pass"], 80.0)
        self.assertEqual(lim["max_failed"], 12)
        v = q.evaluate({"total": 100, "passed": 90, "failed": 10}, None, [], None, lim)
        self.assertEqual(_by_id(v)["pass_rate"]["status"], "pass")
        self.assertEqual(_by_id(v)["failed"]["status"], "pass")

    def test_prefs_feed_limits(self):
        lim = q.parse_limits({}, {"alert_pass_pct": 90, "alert_coverage_pct": 70, "alert_slowdown_pct": 25})
        self.assertEqual((lim["min_pass"], lim["min_coverage"], lim["max_slowdown"]), (90.0, 70.0, 25.0))

    def test_bad_values_raise(self):
        for bad in ({"min_pass": ["x"]}, {"min_pass": ["101"]}, {"max_failed": ["-1"]}, {"min_coverage": ["nan"]}):
            with self.assertRaises(ValueError):
                q.parse_limits(bad)

    def test_booleans_are_not_numbers(self):
        v = q.evaluate({"total": True, "passed": True, "failed": False}, None, [], None, LIM)
        self.assertEqual(v["status"], "unknown")


if __name__ == "__main__":
    unittest.main()
