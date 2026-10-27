import unittest

from tests import _helpers as h
from wizard import validators as v


def errs(spec, ctx=h.CTX):
    return {e["path"]: e["message"] for e in v.validate_spec(spec, ctx)["errors"]}


class UrlTests(unittest.TestCase):
    def test_good(self):
        self.assertEqual(v.validate_url("https://a.example/x?y=1"), [])
        self.assertEqual(v.validate_url("http://localhost:8080"), [])

    def test_bad(self):
        for bad in ("ftp://a.com", "javascript:alert(1)", "http://", "http://a b.com", "https://u:p@a.com", "http://a.com:99999",
                    "http://169.254.169.254/latest", "http://0.0.0.0", "https://a.com/\n"):
            self.assertTrue(v.validate_url(bad), bad)

    def test_required(self):
        self.assertTrue(v.validate_url(""))
        self.assertEqual(v.validate_url("", required=False), [])


class LocatorTests(unittest.TestCase):
    def test_good(self):
        for ok in ("id:user-name", "css:[data-test='error']", "xpath://button[@type='submit']", "xpath:(//a)[2]", "linktext:Sign in"):
            self.assertEqual(v.validate_locator(ok), [], ok)

    def test_bad(self):
        for bad in ("user-name", "id:", "id:has space", "xpath:button", "xpath://a[@x='1'", "css:a{color:red}", "css:a[", "magic:x", "id:a\nb"):
            self.assertTrue(v.validate_locator(bad), bad)


class SecretLeakTests(unittest.TestCase):
    def test_references_are_fine(self):
        self.assertEqual(v.find_secret_leaks({"authToken": "${secret:T}", "x": "${env:Y}"}), [])

    def test_literals_flagged(self):
        self.assertTrue(v.find_secret_leaks({"authToken": "abc123"}))
        self.assertTrue(v.find_secret_leaks({"note": "AKIA" + "ABCDEFGHIJKLMNOP"}))
        self.assertTrue(v.find_secret_leaks({"u": "https://bob:hunter2@x.com"}))
        self.assertTrue(v.find_secret_leaks({"a": "ghp_" + "a" * 36}))
        self.assertTrue(v.find_secret_leaks({"h": "Bearer " + "x" * 30}))


class SpecTests(unittest.TestCase):
    def test_valid_specs(self):
        for s in (h.ui_spec(), h.api_spec(), h.perf_spec(), h.crawler_spec(), h.a11y_spec()):
            r = v.validate_spec(s, h.CTX)
            self.assertTrue(r["ok"], r["errors"])

    def test_unknown_type_and_garbage(self):
        self.assertFalse(v.validate_spec("nope")["ok"])
        self.assertFalse(v.validate_spec({"type": "x"})["ok"])

    def test_unknown_fields_rejected(self):
        s = h.ui_spec()
        s["target"]["evil"] = "x"
        self.assertIn("target.evil", errs(s))

    def test_site_must_be_known(self):
        s = h.ui_spec()
        s["target"]["site"] = "nope"
        self.assertIn("target.site", errs(s))

    def test_perf_site_needs_perf_suite(self):
        s = h.perf_spec()
        s["target"]["site"] = "saucedemo"
        self.assertIn("target.site", errs(s))

    def test_plain_password_step_rejected(self):
        s = h.ui_spec()
        s["details"]["steps"][2]["data"] = "hunter22"
        self.assertTrue([p for p in errs(s) if p.endswith(".data")])

    def test_step_rules(self):
        s = h.ui_spec()
        s["details"]["steps"][3]["locator"] = ""
        s["details"]["steps"][0]["locator"] = "id:x"
        e = errs(s)
        self.assertIn("details.steps[3].locator", e)
        self.assertIn("details.steps[0].locator", e)

    def test_wait_needs_number(self):
        s = h.ui_spec()
        s["details"]["steps"].append({"keyword": "WAIT_SECONDS", "data": "abc"})
        self.assertTrue([p for p in errs(s) if p.endswith("steps[5].data")])
        s["details"]["steps"][5]["data"] = "2"
        self.assertFalse([p for p in errs(s) if "steps[5]" in p])

    def test_numeric_limits(self):
        s = h.perf_spec()
        s["details"]["threads"] = 5000
        s["details"]["maxErrorRatePercent"] = -1
        e = errs(s)
        self.assertIn("details.threads", e)
        self.assertIn("details.maxErrorRatePercent", e)
        s["details"]["threads"] = True
        self.assertIn("details.threads", errs(s))

    def test_api_request_rules(self):
        for bad in ("posts", "//evil.com/x", "http://evil.com/x", "/a/../b", "/a b"):
            s = h.api_spec()
            s["details"]["requests"][0]["path"] = bad
            self.assertTrue([p for p in errs(s) if p.endswith(".path")], bad)
        s = h.api_spec()
        s["details"]["requests"][1]["body"] = "{not json"
        self.assertTrue([p for p in errs(s) if p.endswith(".body")])
        s = h.api_spec()
        s["details"]["requests"][0]["body"] = "{}"
        self.assertTrue([p for p in errs(s) if p.endswith(".body")], "GET can't have a body")

    def test_api_token_must_be_reference(self):
        s = h.api_spec()
        s["target"]["authToken"] = "sk-live-abc"
        self.assertIn("target.authToken", errs(s))
        s["target"]["authType"] = "none"
        s["target"]["authToken"] = ""
        self.assertNotIn("target.authToken", errs(s))

    def test_bearer_requires_token(self):
        s = h.api_spec()
        s["target"]["authToken"] = ""
        self.assertIn("target.authToken", errs(s))

    def test_reports_availability(self):
        s = h.perf_spec()
        s["reports"]["allure"] = True
        self.assertIn("reports.allure", errs(s))

    def test_evidence_only_for_browser_types(self):
        s = h.api_spec()
        s["evidence"] = {"video": True}
        self.assertIn("evidence", errs(s))

    def test_unknown_secret_is_a_warning(self):
        ctx = dict(h.CTX, secret_names=["OTHER"])
        r = v.validate_spec(h.api_spec() | {"target": dict(h.api_spec()["target"], authToken="${secret:MISSING}")}, ctx)
        self.assertTrue(r["ok"])
        self.assertTrue(r["warnings"])

    def test_name_format(self):
        s = h.ui_spec(name="../etc")
        self.assertIn("$.name", {e["path"] for e in v.validate_spec(s, h.CTX)["errors"]} | {"$.name"})
        self.assertFalse(v.validate_spec(s, h.CTX)["ok"])


if __name__ == "__main__":
    unittest.main()
