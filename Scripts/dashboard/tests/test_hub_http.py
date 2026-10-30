"""End to end over HTTP: hub, results of a real (fake-Maven) run, connection check, required switches, secrets."""
import os
import time
import unittest

from tests import _helpers as h
import tests.test_http as th

FAKE_MVN = """#!/bin/sh
mkdir -p target/surefire-reports
echo "ran $@"
echo "API_TOKEN_IS=${API_TOKEN:-unset}"
if [ -f fail.flag ]; then
  echo '<testsuite><testcase classname="a.LoginTest" name="bad" time="1"><failure message="expected [x] but found [y]">t</failure></testcase><testcase classname="a.LoginTest" name="good" time="1"/></testsuite>' > target/surefire-reports/TEST-a.xml
  exit 1
fi
echo '<testsuite><testcase classname="a.LoginTest" name="good" time="1"/></testsuite>' > target/surefire-reports/TEST-a.xml
exit 0
"""


class HubHttpTests(unittest.TestCase):
    tearDownClass = classmethod(th.HttpTests.tearDownClass.__func__)
    call = th.HttpTests.call

    @classmethod
    def setUpClass(cls):  # reuse the HTTP fixture, then swap in a Maven stand-in that writes test results
        th.HttpTests.setUpClass.__func__(cls)
        mvn = os.path.join(os.environ["PATH"].split(os.pathsep)[0], "mvn")
        h.write(mvn, FAKE_MVN)
        os.makedirs(os.path.join(cls.repo, "Scripts"), exist_ok=True)
        h.write(os.path.join(cls.repo, "Scripts", "api-test.sh"), FAKE_MVN)  # same stand-in for the API script

    def wait(self, run_id, secs=20):
        end = time.time() + secs
        while time.time() < end:
            s, st, _ = self.call("GET", f"/api/runs/{run_id}")
            if st["state"] in ("passed", "failed", "error", "cancelled", "timeout"):
                return st
            time.sleep(0.1)
        self.fail("run did not finish")

    def flag(self, on):
        p = os.path.join(self.repo, "fail.flag")
        if on:
            h.write(p, "x")
        elif os.path.exists(p):
            os.remove(p)

    def test_hub_page_assets_and_data(self):
        for path in ("/hub", "/hub/static/hub.js", "/hub/static/results.js", "/hub/static/hub.css"):
            self.assertEqual(self.call("GET", path, raw=True)[0], 200, path)
        s, d, _ = self.call("GET", "/api/hub")
        self.assertEqual(s, 200)
        self.assertEqual(len(d["intents"]), 10)
        self.assertTrue({"runner", "maven", "java"} <= {r["id"] for r in d["readiness"]})
        self.assertIn("open", d["intents"][0])
        self.assertEqual(self.call("GET", "/api/hub", user=None)[0], 401)
        self.assertEqual(self.call("GET", "/hub/static/../../auth/users.py", raw=True)[0], 404)

    def test_every_hub_card_points_at_something_real(self):
        s, d, _ = self.call("GET", "/api/hub")
        types = {t["type"] for t in self.call("GET", "/api/wizard/options")[1]["types"]}
        feats = self.call("GET", "/api/features")[1]
        groups = set(feats["groups"])
        for i in d["intents"]:
            if i["open"].startswith("/wizard?type="):
                self.assertIn(i["open"].split("=")[1], types, i["id"])
            elif "group=" in i["open"]:
                from urllib.parse import unquote
                self.assertIn(unquote(i["open"].split("group=")[1]), groups, i["id"])

    def test_failed_run_has_counts_failures_and_results_endpoint(self):
        self.flag(True)
        s, st, _ = self.call("POST", "/api/features/run", {"feature": "unit-tests", "options": {}})
        self.assertEqual(s, 200, st)
        done = self.wait(st["id"])
        self.assertEqual((done["state"], done["verdict"]), ("failed", "failed"))
        self.assertEqual(done["counts"], {"total": 2, "passed": 1, "failed": 1, "skipped": 0})
        s, r, _ = self.call("GET", f"/api/runs/{st['id']}/results")
        self.assertEqual(s, 200)
        self.assertEqual(r["headline"][:20], "1 of 2 checks failed")
        self.assertEqual(r["failures"][0]["test"], "bad")
        self.assertEqual(r["failures"][0]["message"], "expected [x] but found [y]")
        s, page, _ = self.call("GET", f"/api/runs/{st['id']}/files/summary.html", raw=True)
        self.assertIn(b"Checks that failed", page)
        self.assertIn(b"expected [x] but found [y]", page)

    def test_passed_run_and_stale_results_are_not_reused(self):
        self.flag(False)
        s, st, _ = self.call("POST", "/api/features/run", {"feature": "unit-tests", "options": {}})
        done = self.wait(st["id"])
        self.assertEqual(done["verdict"], "passed")
        self.assertIn("All 1 checks passed", done["headline"])
        # a housekeeping feature writes no results: it must not borrow the previous run's XML
        old = time.time() - 600
        os.utime(os.path.join(self.repo, "target", "surefire-reports", "TEST-a.xml"), (old, old))
        s, st2, _ = self.call("POST", "/api/features/run", {"feature": "maven-version", "options": {}})
        d2 = self.wait(st2["id"])
        self.assertEqual((d2["verdict"], d2["counts"]), ("passed", None))

    def test_results_of_an_unknown_run_is_404(self):
        self.assertEqual(self.call("GET", "/api/runs/20200101-000000-abcdef/results")[0], 404)

    def test_required_switch_is_enforced_by_the_server(self):
        for feat in ("bug-crawler", "api-openapi"):
            s, d, _ = self.call("POST", "/api/features/run", {"feature": feat, "options": {}})
            self.assertEqual(s, 400, feat)
            self.assertIn("is needed before this can run", d["error"])
        s, d, _ = self.call("POST", "/api/features/run", {"feature": "new-site", "options": {"name": "shop"}})
        self.assertEqual(s, 400)
        feats = {f["id"]: f for f in self.call("GET", "/api/features")[1]["features"]}
        self.assertTrue([o for o in feats["bug-crawler"]["options"] if o["key"] == "crawler.startUrl"][0]["required"])

    def test_secret_for_openapi_feature_reaches_the_run_but_never_the_command_or_log(self):
        try:
            from cryptography.fernet import Fernet
        except ImportError:
            self.skipTest("cryptography not installed")
        os.environ["DASHBOARD_SECRET_KEY"] = Fernet.generate_key().decode()
        try:
            self.call("POST", "/api/secrets", {"name": "PET_TOKEN", "value": "s3cr3t-token-value"}, user="adam", pw="admin-password-1")
            self.flag(False)
            s, st, _ = self.call("POST", "/api/features/run", {"feature": "api-openapi", "options": {
                "openapi": "https://petstore3.swagger.io/api/v3/openapi.json", "API_TOKEN": "${secret:PET_TOKEN}"}})
            self.assertEqual(s, 200, st)
            done = self.wait(st["id"])
            self.assertEqual(done["state"], "passed", done)
            self.assertNotIn("s3cr3t-token-value", done["command"])
            s, log, _ = self.call("GET", f"/api/runs/{st['id']}/log?offset=0")
            self.assertIn("API_TOKEN_IS=***", log["text"])
            self.assertNotIn("s3cr3t-token-value", log["text"])
        finally:
            os.environ.pop("DASHBOARD_SECRET_KEY", None)

    def test_env_reference_is_copied_under_the_fixed_name(self):
        os.environ["MY_PET_TOKEN"] = "env-token-value"
        try:
            self.flag(False)
            s, st, _ = self.call("POST", "/api/features/run", {"feature": "api-openapi", "options": {
                "openapi": "https://x.test/o.json", "API_TOKEN": "${env:MY_PET_TOKEN}"}})
            self.assertEqual(s, 200, st)
            self.assertEqual(self.wait(st["id"])["state"], "passed")
            log = self.call("GET", f"/api/runs/{st['id']}/log?offset=0")[1]["text"]
            self.assertIn("API_TOKEN_IS=env-token-value", log)
        finally:
            os.environ.pop("MY_PET_TOKEN", None)

    def test_a_literal_token_is_refused_for_the_feature(self):
        s, d, _ = self.call("POST", "/api/features/run", {"feature": "api-openapi", "options": {
            "openapi": "https://x.test/o.json", "API_TOKEN": "abc123literal"}})
        self.assertEqual(s, 400)
        self.assertIn("saved-secret reference", d["error"])

    def test_check_url_endpoint(self):
        s, d, _ = self.call("POST", "/api/wizard/check-url", {"url": self.base + "/hub"})
        self.assertEqual(s, 200)
        self.assertTrue(d["ok"])  # the dashboard itself answers (401 without credentials) -> reachable
        s, d, _ = self.call("POST", "/api/wizard/check-url", {"url": "http://169.254.169.254/"})
        self.assertFalse(d["ok"])
        s, d, _ = self.call("POST", "/api/wizard/check-url", {})
        self.assertFalse(d["ok"])
        self.assertEqual(self.call("POST", "/api/wizard/check-url", {"url": "http://x"}, user=None)[0], 401)


if __name__ == "__main__":
    unittest.main()
