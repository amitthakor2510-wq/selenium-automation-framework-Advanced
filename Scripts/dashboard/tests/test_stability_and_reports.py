"""Test-stability ranking and the Reports panel's server data."""
import json
import os
import tempfile
import unittest

import results_history as rh
import stability
import system_info as si
from tests import _helpers as h


def runs(*failing_per_run):
    return [{"t": i, "sig": str(i), "rate": 50, "fails": list(f)} for i, f in enumerate(failing_per_run)]


class ClassifyTests(unittest.TestCase):
    def test_never_failed_is_not_ranked(self):
        self.assertIsNone(stability.classify(["pass", "pass", "skip"]))

    def test_flip_flopping_is_flaky(self):
        self.assertEqual(stability.classify(["pass", "fail", "pass", "fail"])[0], "flaky")

    def test_failing_every_run_needs_three_runs_to_be_consistent(self):
        self.assertEqual(stability.classify(["fail", "fail"])[0], "new")
        self.assertEqual(stability.classify(["fail", "fail", "fail"])[0], "consistent")

    def test_started_failing_and_recovered(self):
        self.assertEqual(stability.classify(["pass", "pass", "fail"])[0], "new")
        self.assertEqual(stability.classify(["fail", "pass", "pass"])[0], "recovered")


class RankTests(unittest.TestCase):
    def test_flaky_ranks_before_consistent_before_recovered(self):
        history = runs({"A.flaky", "A.always", "A.old"}, {"A.always", "A.old"}, {"A.flaky", "A.always"}, {"A.always", "A.flaky"})
        # A.old: fail, fail, pass, pass -> recovered; A.flaky: fail,pass,fail,fail -> flaky
        out = stability.rank(history, {})
        self.assertEqual([e["test"] for e in out], ["A.flaky", "A.always", "A.old"])
        self.assertEqual([e["kind"] for e in out], ["flaky", "consistent", "recovered"])
        self.assertEqual(out[0]["recent"], ["fail", "pass", "fail", "fail"])
        self.assertEqual(out[0]["source"], "local")

    def test_old_history_entries_without_fails_are_ignored_not_counted_as_passes(self):
        history = [{"t": 1, "sig": "a", "rate": 90}] + runs({"A.x"}, set(), {"A.x"})
        out = stability.rank(history, {})
        self.assertEqual(out[0]["runs"], 3)

    def test_ci_history_wins_over_local_for_the_same_test(self):
        ci = {"flaky": [{"test": "A.x", "recent_statuses": ["pass", "fail", "pass"], "pass_count": 2, "fail_count": 1}]}
        out = stability.rank(runs({"A.x"}, {"A.x"}, {"A.x"}), ci)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["source"], "ci")
        self.assertEqual(out[0]["kind"], "flaky")

    def test_ci_entries_with_only_counts_and_garbage_are_handled(self):
        ci = {"flaky": [{"test": "A.y", "pass_count": 3, "fail_count": 2}, {"test": "A.z", "pass_count": 5, "fail_count": 0},
                        {"test": 7}, "junk", None]}
        out = stability.rank([], ci)
        self.assertEqual([e["test"] for e in out], ["A.y"])
        self.assertEqual(stability.rank([], None), [])
        self.assertEqual(stability.rank([], {"flaky": "x"}), [])

    def test_result_is_capped(self):
        many = runs({f"A.t{i}" for i in range(200)})
        self.assertEqual(len(stability.rank(many, {})), stability.MAX_RESULTS)


class HistoryFailsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["DASHBOARD_DATA_DIR"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("DASHBOARD_DATA_DIR", None))

    def test_record_stores_capped_failing_names(self):
        repo = tempfile.mkdtemp()
        sf = {"total": 100, "passed": 40, "failed": 60, "skipped": 0, "failures": [f"A.t{i}" for i in range(60)]}
        entries = rh.record(repo, sf, now=1000)
        self.assertEqual(len(entries[-1]["fails"]), rh.MAX_FAILS_PER_RUN)

    def test_damaged_fails_field_is_sanitised_on_read(self):
        good = {"t": 1, "sig": "s", "rate": 50}
        with open(rh.history_path(), "w") as f:
            json.dump([{**good, "fails": "notalist"}, {**good, "t": 2, "fails": ["A.x", 5, None, "B" * 500]}], f)
        out = rh.read()
        self.assertEqual(out[0]["fails"], [])
        self.assertEqual(out[1]["fails"][0], "A.x")
        self.assertEqual(len(out[1]["fails"]), 2)
        self.assertEqual(len(out[1]["fails"][1]), 200)


class ReportsTests(unittest.TestCase):
    def repo_with_origin(self, url):
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, ".git"))
        with open(os.path.join(d, ".git", "config"), "w") as f:
            f.write(f'[core]\n\trepositoryformatversion = 0\n[remote "origin"]\n\turl = {url}\n')
        return d

    def test_github_remote_maps_to_pages_url_with_lowercase_host(self):
        for url in ("git@github.com:Alpha2122ff/selenium-framework.git", "https://github.com/Alpha2122ff/selenium-framework.git",
                    "https://github.com/Alpha2122ff/selenium-framework"):
            with self.subTest(url=url):
                self.assertEqual(si.published_reports_url(self.repo_with_origin(url), {}),
                                 {"url": "https://alpha2122ff.github.io/selenium-framework/", "source": "github"})

    def test_user_site_repo_maps_to_the_root(self):
        r = si.published_reports_url(self.repo_with_origin("https://github.com/me/me.github.io.git"), {})
        self.assertEqual(r["url"], "https://me.github.io/")

    def test_non_github_or_missing_remote_gives_none(self):
        self.assertIsNone(si.published_reports_url(self.repo_with_origin("https://gitlab.example.com/a/b.git"), {}))
        self.assertIsNone(si.published_reports_url(tempfile.mkdtemp(), {}))

    def test_env_override_wins_and_only_plain_http_urls_are_accepted(self):
        repo = self.repo_with_origin("https://github.com/a/b.git")
        self.assertEqual(si.published_reports_url(repo, {"DASHBOARD_REPORTS_URL": "https://ci.example.com:8443/r/"})["source"], "env")
        for bad in ("javascript:alert(1)", "data:text/html,x", "ftp://x.example.com", "https://a.example.com/\"onmouseover=\"x",
                    "https://a.example.com/<script>", "//evil.example.com", "https://user:pw@evil.example.com/"):
            with self.subTest(bad=bad):
                r = si.published_reports_url(repo, {"DASHBOARD_REPORTS_URL": bad})
                self.assertEqual(r["source"], "github", "an unsafe override must be ignored, not used")

    def test_reports_carry_modified_time_and_open_hint_only_when_present(self):
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, "target", "allure-report"))
        with open(os.path.join(d, "target", "allure-report", "index.html"), "w") as f:
            f.write("x")
        by_path = {r["path"]: r for r in si.reports_present(d)}
        ar = by_path["target/allure-report"]
        self.assertTrue(ar["present"])
        self.assertIsInstance(ar["modified"], int)
        self.assertEqual(ar["open_hint"], "allure open target/allure-report")
        self.assertFalse(by_path["target/extent-reports"]["present"])
        self.assertIsNone(by_path["target/extent-reports"]["modified"])


class WiringTests(unittest.TestCase):
    def test_ui_elements_exist_for_new_panels(self):
        html = h.read(os.path.join(h.DASH, "static", "index.html"))
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        for element_id in ("reportsPanel", "reportsList", "reportsFoot", "reportsPublished", "reportsPublishedNote", "flakyList"):
            self.assertIn(f'id="{element_id}"', html)
        self.assertIn("renderFlaky(data.flaky, data.stability)", app)
        self.assertNotIn(" style=", html)


if __name__ == "__main__":
    unittest.main()
