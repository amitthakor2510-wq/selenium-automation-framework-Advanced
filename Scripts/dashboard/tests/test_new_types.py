"""openapi + mobile test types, the extended API form, header parsing, and the credential env rules."""
import os
import unittest

from tests import _helpers as h
from runner import allowlist as al
from specs.converters import convert, to_mobile
from wizard import validators as v


def openapi_spec(**target):
    t = {"openapiUrl": "https://petstore3.swagger.io/api/v3/openapi.json", "authType": "none"}
    t.update(target)
    return {"version": 1, "name": "Petstore", "type": "openapi", "target": t,
            "details": {"negative": False, "retries": 0}, "reports": {"allure": False, "summary": True},
            "evidence": {}, "run": {"timeoutMinutes": 5}}


def mobile_spec(**target):
    t = {"platform": "android", "appPackage": "com.example.shop"}
    t.update(target)
    return {"version": 1, "name": "Shop app", "type": "mobile", "target": t,
            "details": {"maxScreens": 10, "maxDepth": 3}, "reports": {"summary": True}, "evidence": {},
            "run": {"timeoutMinutes": 10}}


def errs(spec):
    return {e["path"]: e["message"] for e in v.validate_spec(spec, h.CTX)["errors"]}


class OpenApiTests(unittest.TestCase):
    def test_minimum_is_just_the_document_link(self):
        self.assertTrue(v.validate_spec(openapi_spec(), h.CTX)["ok"])

    def test_link_is_required_and_must_be_a_url(self):
        s = openapi_spec()
        del s["target"]["openapiUrl"]
        self.assertIn("target.openapiUrl", errs(s))
        self.assertIn("target.openapiUrl", errs(openapi_spec(openapiUrl="not a url")))

    def test_auth_reference_is_required_only_once_an_auth_type_is_chosen(self):
        self.assertIn("target.authToken", errs(openapi_spec(authType="bearer")))
        self.assertTrue(v.validate_spec(openapi_spec(authType="bearer", authToken="${secret:PET_TOKEN}"), h.CTX)["ok"])
        self.assertIn("target.authPassword", errs(openapi_spec(authType="basic", authUser="${env:U}")))

    def test_a_literal_token_is_refused(self):
        self.assertIn("target.authToken", errs(openapi_spec(authType="bearer", authToken="abc123")))

    def test_converter_maps_secret_and_env_references_to_the_variables_the_tests_read(self):
        plan = convert(openapi_spec(authType="basic", authUser="${secret:U}", authPassword="${env:MY_PW}"), None)
        self.assertEqual(plan["secret_env"], {"API_USER": "U"})
        self.assertEqual(plan["env_alias"], {"API_PASSWORD": "MY_PW"})

    def test_command_has_no_secret_and_only_fixed_flags(self):
        spec = openapi_spec(baseUrl="https://stg.example.com", authType="bearer", authToken="${secret:T}")
        spec["details"].update(negative=True, maxTimeMs=2500, retries=2)
        plan = convert(spec, None)
        argv = al.build_command("openapi", plan, h.DASH, "/tmp")
        self.assertEqual(argv, ["bash", "Scripts/api-test.sh", "--openapi", spec["target"]["openapiUrl"], "--base-url",
                                "https://stg.example.com", "--negative", "--max-time", "2500", "--retry", "2"])
        self.assertNotIn("T", argv)

    def test_allowlist_rejects_anything_unexpected(self):
        for plan in ({"openapi_url": "file:///etc/passwd"}, {"openapi_url": "https://x.test/o", "options": {"shell": "x"}},
                     {"openapi_url": "https://x.test/o", "options": {"retry": 9}},
                     {"openapi_url": "https://x.test/o", "options": {"base_url": "ftp://x"}}):
            with self.assertRaises(al.NotAllowed):
                al.build_command("openapi", plan, h.DASH, "/tmp")


class CredentialEnvTests(unittest.TestCase):
    def test_credentials_land_under_the_fixed_names_only(self):
        env = al.build_env({"env_alias": {"API_TOKEN": "MY_TOKEN"}}, {"API_USER": "alice"}, environ={"MY_TOKEN": "t0k", "PATH": "/bin"})
        self.assertEqual((env["API_TOKEN"], env["API_USER"]), ("t0k", "alice"))

    def test_other_names_and_dashboard_secrets_are_refused(self):
        with self.assertRaises(al.NotAllowed):
            al.build_env({"env_alias": {"LD_PRELOAD": "X"}}, {}, environ={"X": "1"})
        with self.assertRaises(al.NotAllowed):
            al.build_env({"env_alias": {"API_TOKEN": "DASHBOARD_PASSWORD"}}, {}, environ={"DASHBOARD_PASSWORD": "p"})
        with self.assertRaises(al.NotAllowed):
            al.build_env({}, {"PATH": "evil"}, environ={})


class FakeMvn(unittest.TestCase):
    """allowlist looks for a Maven launcher; give it a harmless stand-in on PATH."""
    def setUp(self):
        self._tmp = h.tmpdir()
        mvn = os.path.join(self._tmp.name, "mvn")
        h.write(mvn, "#!/bin/sh\n")
        os.chmod(mvn, 0o755)
        self._path = os.environ["PATH"]
        os.environ["PATH"] = self._tmp.name + os.pathsep + self._path

    def tearDown(self):
        os.environ["PATH"] = self._path
        self._tmp.cleanup()


class MobileTests(FakeMvn):
    def test_package_alone_is_enough(self):
        self.assertTrue(v.validate_spec(mobile_spec(), h.CTX)["ok"])

    def test_package_or_file_is_needed(self):
        s = mobile_spec()
        del s["target"]["appPackage"]
        self.assertIn("target.appPackage", errs(s))
        with h.tmpdir() as d:
            apk = os.path.join(d, "shop.apk")
            h.write(apk, "x")
            s["target"]["appPath"] = apk
            self.assertTrue(v.validate_spec(s, h.CTX)["ok"])

    def test_missing_app_file_is_a_warning_not_an_error(self):
        s = mobile_spec(appPath="/nowhere/shop.apk")
        r = v.validate_spec(s, h.CTX)
        self.assertTrue(r["ok"])
        self.assertTrue(any(w["path"] == "target.appPath" for w in r["warnings"]))

    def test_field_formats(self):
        self.assertIn("target.appPackage", errs(mobile_spec(appPackage="shop")))
        self.assertIn("target.appPath", errs(mobile_spec(appPackage="", appPath="/x/shop.exe")))
        self.assertIn("target.deviceName", errs(mobile_spec(deviceName="a;rm -rf")))
        self.assertIn("details.avoidWords", {k: 1 for k in errs({**mobile_spec(), "details": {"avoidWords": "a;b"}})})

    def test_extra_avoid_words_only_add_to_the_safety_list(self):
        out = to_mobile.avoid_list("Checkout, send, PAY")
        self.assertTrue(out.startswith(to_mobile.DEFAULT_AVOID))
        self.assertTrue(out.endswith("Checkout,send"))  # 'PAY' was already in the default list (case-insensitive)

    def test_default_avoid_list_matches_the_framework_default(self):
        gp = h.read(os.path.join(h.DASH, "..", "..", "src", "test", "resources", "config", "global.properties"))
        line = [l for l in gp.splitlines() if l.startswith("crawler.mobile.avoidTextContains=")][0]
        self.assertEqual(line.split("=", 1)[1], to_mobile.DEFAULT_AVOID)

    def test_converter_and_command(self):
        plan = convert(mobile_spec(deviceName="Pixel 7", noReset=True), None)
        p = plan["properties"]
        self.assertEqual((p["mobile.platform"], p["mobile.app.package"], p["mobile.device.name"], p["mobile.noReset"]),
                         ("android", "com.example.shop", "Pixel 7", "true"))
        argv = al.build_command("mobile", dict(plan, suite_xml=None), h.DASH, "/tmp")
        self.assertEqual(argv[1:6], ["-B", "-q", "-Pmobile-bug-crawler", "exec:java@mobile-bug-crawler", "-Dsite=mobile"])
        self.assertIn("-Dmobile.app.package=com.example.shop", argv)

    def test_allowlist_rejects_bad_mobile_values(self):
        for props in ({"mobile.platform": "windows"}, {"mobile.app.path": "/etc/passwd"}, {"mobile.app.path": "rel/x.apk"},
                      {"mobile.app.path": "/tmp/../etc/x.apk"}, {"mobile.app.package": "x;y"}, {"unknown.key": "1"}):
            with self.assertRaises(al.NotAllowed, msg=str(props)):
                al.build_command("mobile", {"properties": props}, h.DASH, "/tmp")

    def test_app_file_must_exist(self):
        with h.tmpdir() as d:
            apk = os.path.join(d, "a.apk")
            with self.assertRaises(al.NotAllowed):
                al.build_command("mobile", {"properties": {"mobile.app.path": apk}}, h.DASH, d)
            h.write(apk, "x")
            self.assertIn("-Dmobile.app.path=" + apk, al.build_command("mobile", {"properties": {"mobile.app.path": apk}}, h.DASH, d))


class ApiExtrasTests(unittest.TestCase):
    def _spec(self, **target):
        s = h.api_spec()
        s["target"].update(target)
        return s

    def test_existing_api_spec_still_valid(self):
        self.assertTrue(v.validate_spec(h.api_spec(), h.CTX)["ok"])

    def test_basic_and_apikey_need_their_references(self):
        self.assertIn("target.authPassword", errs(self._spec(authType="basic", authUser="${env:U}")))
        self.assertIn("target.apiKeyName", errs(self._spec(authType="apiKey", apiKeyValue="${env:K}")))
        self.assertTrue(v.validate_spec(self._spec(authType="apiKey", apiKeyName="X-API-Key", apiKeyValue="${secret:K}", apiKeyIn="query"),
                                        h.CTX)["ok"])

    def test_headers_parse_and_validate(self):
        self.assertEqual(v.parse_headers("X-Api-Version: 2\n\nAccept-Language: en"), [("X-Api-Version", "2"), ("Accept-Language", "en")])
        for bad in ("no colon here", "Host: evil", "Bad Name: x", "Authorization: Bearer abcdefghijklmnopqrstuvwxyz", "X-Api-Key: plain"):
            self.assertTrue(v.validate_headers(bad), bad)
        self.assertEqual(v.validate_headers("Authorization: ${secret:TOK}"), [])
        self.assertTrue(v.validate_headers("\n".join(f"X-H{i}: v" for i in range(25))))

    def test_converter_writes_auth_headers_retry_and_insecure(self):
        import json
        with h.tmpdir() as d:
            plan = convert(self._spec(authType="basic", authUser="${secret:U}", authPassword="${secret:P}", retries=2,
                                      insecureTls=True, extraHeaders="X-Env: ${secret:ENVNAME}\nX-Plain: yes"), d)
            doc = json.loads(h.read(plan["spec_file"]))
        self.assertEqual(doc["auth"], {"type": "basic", "username": "${env:WIZARD_SECRET_U}", "password": "${env:WIZARD_SECRET_P}"})
        self.assertEqual(doc["defaults"]["retry"], {"count": 2})
        self.assertTrue(doc["defaults"]["insecure"])
        self.assertEqual(doc["headers"], {"X-Env": "${env:WIZARD_SECRET_ENVNAME}", "X-Plain": "yes"})
        self.assertEqual(plan["secret_env"], {"WIZARD_SECRET_U": "U", "WIZARD_SECRET_P": "P", "WIZARD_SECRET_ENVNAME": "ENVNAME"})

    def test_apikey_in_spec(self):
        import json
        with h.tmpdir() as d:
            plan = convert(self._spec(authType="apiKey", apiKeyName="X-Key", apiKeyValue="${secret:K}", apiKeyIn="query"), d)
            self.assertEqual(json.loads(h.read(plan["spec_file"]))["auth"],
                             {"type": "apiKey", "name": "X-Key", "value": "${env:WIZARD_SECRET_K}", "in": "query"})


if __name__ == "__main__":
    unittest.main()
