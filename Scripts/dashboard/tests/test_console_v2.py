"""Regression tests for the Console review: strict request validation, hardening headers, /api/system,
and per-testcase result details. Each test pins a defect that was found and fixed, so it cannot return."""
import base64
import http.client
import json
import os
import shutil
import threading
import unittest
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds
import results
import system_info

REAL_ROOT = os.path.abspath(os.path.join(h.DASH, "..", ".."))
PW = "console-test-password-1"


class ConsoleHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._t = h.tmpdir()
        root = cls._t.name
        cls.repo = os.path.join(root, "repo")
        os.makedirs(cls.repo)
        for f in ("pipeline-config.properties", "test-config.properties"):
            shutil.copy(os.path.join(REAL_ROOT, f), cls.repo)
        shutil.copytree(os.path.join(REAL_ROOT, "testng-suites"), os.path.join(cls.repo, "testng-suites"))
        cls._env = dict(os.environ)
        os.environ.update({"DASHBOARD_DATA_DIR": os.path.join(root, "data"),
                           "DASHBOARD_USERS_FILE": os.path.join(root, "users.json"),
                           "DASHBOARD_RUNNER_ENABLED": ""})
        cls._saved = (ds.REPO_ROOT, ds.PIPELINE_CONFIG, ds.TEST_CONFIG, ds.SUITES_DIR)
        ds.REPO_ROOT = cls.repo
        ds.PIPELINE_CONFIG = os.path.join(cls.repo, "pipeline-config.properties")
        ds.TEST_CONFIG = os.path.join(cls.repo, "test-config.properties")
        ds.SUITES_DIR = os.path.join(cls.repo, "testng-suites")
        handler = ds.make_handler(PW, None)
        handler.log_message = lambda self, fmt, *args: None
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.auth = "Basic " + base64.b64encode(f"dashboard:{PW}".encode()).decode()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        ds.REPO_ROOT, ds.PIPELINE_CONFIG, ds.TEST_CONFIG, ds.SUITES_DIR = cls._saved
        os.environ.clear()
        os.environ.update(cls._env)
        cls._t.cleanup()

    def req(self, method, path, body=None, headers=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        hdrs = {"Authorization": self.auth}
        if method == "POST":
            hdrs["Content-Type"] = "application/json"
        hdrs.update(headers or {})
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        payload = resp.read()
        out = (resp.status, dict(resp.getheaders()), payload)
        conn.close()
        return out

    def j(self, method, path, body=None, **kw):
        status, hdrs, payload = self.req(method, path, body, **kw)
        return status, (json.loads(payload) if payload else {})

    def site_enabled(self, name):
        _s, data = self.j("GET", "/api/pipeline-config")
        return next(x["enabled"] for x in data["sites"] if x["name"] == name)

    # ── strict validation ─────────────────────────────────────────────────────────────────────────
    def test_string_false_is_rejected_not_treated_as_true(self):
        before = self.site_enabled("saucedemo")
        status, data = self.j("POST", "/api/pipeline-config/site", {"name": "saucedemo", "enabled": "false"})
        self.assertEqual(status, 400)
        self.assertIn("true or false", data["error"])
        self.assertEqual(self.site_enabled("saucedemo"), before)

    def test_missing_enabled_does_not_silently_disable(self):
        before = self.site_enabled("saucedemo")
        status, _ = self.j("POST", "/api/pipeline-config/site", {"name": "saucedemo"})
        self.assertEqual(status, 400)
        self.assertEqual(self.site_enabled("saucedemo"), before)

    def test_run_only_requires_a_value_and_a_string(self):
        self.assertEqual(self.j("POST", "/api/test-config/run-only", {"value": "LoginTest"})[0], 200)
        self.assertEqual(self.j("POST", "/api/test-config/run-only", {})[0], 400)
        self.assertEqual(self.j("POST", "/api/test-config/run-only", {"value": 123})[0], 400)
        self.assertEqual(self.j("GET", "/api/test-config")[1]["run_only"], "LoginTest")  # untouched by the bad calls
        self.assertEqual(self.j("POST", "/api/test-config/run-only", {"value": ""})[0], 200)  # explicit clear still works

    def test_wrong_types_are_400_not_500(self):
        for path, body in (("/api/test-config/group", {"name": ["x"], "enabled": True}),
                           ("/api/test-config/test", {"name": {"a": 1}, "enabled": True}),
                           ("/api/pipeline-config/site", {"name": None, "enabled": True}),
                           ("/api/test-config/bulk-section", {"names": [1, 2], "enabled": True}),
                           ("/api/test-config/bulk-section", {"names": ["A"]})):
            with self.subTest(path=path, body=body):
                self.assertEqual(self.j("POST", path, body)[0], 400)

    def test_chunked_body_is_refused_instead_of_read_as_empty(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.putrequest("POST", "/api/test-config/run-only")
        conn.putheader("Authorization", self.auth)
        conn.putheader("Content-Type", "application/json")
        conn.putheader("Transfer-Encoding", "chunked")
        conn.endheaders()
        conn.send(b'7\r\n{"a":1}\r\n0\r\n\r\n')
        self.assertEqual(conn.getresponse().status, 411)
        conn.close()

    def test_valid_requests_still_work(self):
        original = self.site_enabled("saucedemo")
        status, data = self.j("POST", "/api/pipeline-config/site", {"name": "saucedemo", "enabled": not original})
        self.assertEqual(status, 200)
        self.assertEqual(next(x["enabled"] for x in data["sites"] if x["name"] == "saucedemo"), not original)
        self.j("POST", "/api/pipeline-config/site", {"name": "saucedemo", "enabled": original})

    # ── headers ───────────────────────────────────────────────────────────────────────────────────
    def test_hardening_headers_on_pages_and_api(self):
        for path in ("/", "/api/health"):
            _s, hdrs, _b = self.req("GET", path)
            with self.subTest(path=path):
                self.assertIn("object-src 'none'", hdrs["Content-Security-Policy"])
                self.assertEqual(hdrs["Cross-Origin-Opener-Policy"], "same-origin")
                self.assertEqual(hdrs["Cross-Origin-Resource-Policy"], "same-origin")
                self.assertIn("camera=()", hdrs["Permissions-Policy"])

    def test_static_assets_are_revalidated(self):
        status, hdrs, _ = self.req("GET", "/app.js")
        self.assertEqual(status, 200)
        self.assertEqual(hdrs["Cache-Control"], "no-cache")

    def test_page_ships_no_inline_script_or_style_attributes(self):
        # The CSP forbids both; a regression here would silently break the page in a real browser.
        text = h.read(os.path.join(ds.STATIC_DIR, "index.html"))
        self.assertNotIn(" style=", text)
        self.assertNotRegex(text, r"<script(?![^>]*\bsrc=)[^>]*>")
        self.assertNotRegex(text, r"\son[a-z]+=")

    # ── /api/system + /api/results details ────────────────────────────────────────────────────────
    def test_system_endpoint_shape_and_auth(self):
        status, data = self.j("GET", "/api/system")
        self.assertEqual(status, 200)
        self.assertEqual(data["auth"]["user"], "dashboard")
        self.assertEqual(data["auth"]["role"], "admin")
        self.assertFalse(data["runner"]["enabled"])
        self.assertIn("uptime_s", data["server"])
        self.assertEqual({r["name"] for r in data["reports"]} >= {"Surefire reports"}, True)
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/api/system")
        self.assertEqual(conn.getresponse().status, 401)
        conn.close()

    def test_results_payload_carries_details(self):
        _s, data = self.j("GET", "/api/results")
        self.assertEqual(set(data["details"]), {"duration_s", "class_time", "slowest", "failure_details"})


class TestcaseDetailsTests(unittest.TestCase):
    PER_CLASS = ('<testsuite name="x"><testcase classname="com.x.A" name="ok" time="1.5"/>'
                 '<testcase classname="com.x.A" name="bad" time="0.2"><failure message="boom &lt;b&gt;" type="E">trace</failure></testcase>'
                 '<testcase classname="com.x.A" name="skipped" time="0"><skipped/></testcase></testsuite>')

    def setUp(self):
        self._t = h.tmpdir()
        self.root = self._t.name
        self.folder = os.path.join(self.root, "target", "surefire-reports")
        os.makedirs(self.folder)

    def tearDown(self):
        self._t.cleanup()

    def put(self, name, text):
        with open(os.path.join(self.folder, name), "w", encoding="utf-8") as f:
            f.write(text)

    def test_aggregate_suite_file_is_not_double_counted(self):
        self.put("TEST-com.x.A.xml", self.PER_CLASS)
        self.put("TEST-TestSuite.xml", self.PER_CLASS)  # the aggregate the totals parser also skips
        d = results.testcase_details(self.root)
        self.assertEqual(d["duration_s"], 1.7)
        self.assertEqual(d["class_time"], {"com.x.A": 1.7})
        self.assertEqual(len(d["failure_details"]), 1)

    def test_failure_details_and_slowest(self):
        self.put("TEST-com.x.A.xml", self.PER_CLASS)
        d = results.testcase_details(self.root)
        self.assertEqual(d["failure_details"][0]["message"], "boom <b>")  # raw text; the UI escapes it
        self.assertEqual(d["failure_details"][0]["kind"], "failure")
        self.assertEqual(d["slowest"][0]["name"], "ok")

    def test_error_without_message_uses_first_trace_line(self):
        self.put("TEST-com.x.B.xml", '<testsuite name="B"><testcase classname="com.x.B" name="e" time="1">'
                                     '<error type="NPE">java.lang.NullPointerException\n  at X.y(X.java:1)</error></testcase></testsuite>')
        d = results.testcase_details(self.root)
        self.assertEqual((d["failure_details"][0]["kind"], d["failure_details"][0]["message"]),
                         ("error", "java.lang.NullPointerException"))

    def test_skipped_case_is_not_a_failure(self):
        self.put("TEST-com.x.C.xml", '<testsuite name="C"><testcase classname="com.x.C" name="s" time="0"><skipped/></testcase></testsuite>')
        self.assertEqual(results.testcase_details(self.root)["failure_details"], [])

    def test_malformed_files_and_missing_folder_degrade_to_empty(self):
        self.put("TEST-bad.xml", "<not xml")
        self.put("TEST-wrong-root.xml", "<other/>")
        empty = {"duration_s": 0.0, "class_time": {}, "slowest": [], "failure_details": []}
        self.assertEqual(results.testcase_details(self.root), empty)
        self.assertEqual(results.testcase_details(os.path.join(self.root, "nope")), empty)

    def test_control_characters_and_length_are_sanitised(self):
        self.put("TEST-com.x.D.xml", '<testsuite name="D"><testcase classname="com.x.D" name="n" time="1">'
                                     '<failure message="' + "a" * 900 + '" type="T"/></testcase></testsuite>')
        msg = results.testcase_details(self.root)["failure_details"][0]["message"]
        self.assertLessEqual(len(msg), 400)
        self.assertTrue(msg.endswith("\u2026"))
        self.assertEqual(results._clean("a\x00b\x07c", 50), "abc")


class SystemInfoTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.root = self._t.name

    def tearDown(self):
        self._t.cleanup()

    def git(self, head, refs=None, packed=None):
        g = os.path.join(self.root, ".git")
        os.makedirs(os.path.join(g, "refs", "heads"))
        with open(os.path.join(g, "HEAD"), "w") as f:
            f.write(head)
        for name, sha in (refs or {}).items():
            with open(os.path.join(g, "refs", "heads", name), "w") as f:
                f.write(sha + "\n")
        if packed:
            with open(os.path.join(g, "packed-refs"), "w") as f:
                f.write("# pack-refs\n" + packed)

    def test_branch_and_commit_from_loose_ref(self):
        self.git("ref: refs/heads/main\n", {"main": "a" * 40})
        self.assertEqual(system_info.git_info(self.root), {"branch": "main", "commit": "a" * 8, "detached": False})

    def test_commit_from_packed_refs(self):
        self.git("ref: refs/heads/release/1.0\n", packed="b" * 40 + " refs/heads/release/1.0\n")
        info = system_info.git_info(self.root)
        self.assertEqual((info["branch"], info["commit"]), ("release/1.0", "b" * 8))

    def test_detached_head(self):
        self.git("c" * 40 + "\n")
        self.assertEqual(system_info.git_info(self.root), {"branch": None, "commit": "c" * 8, "detached": True})

    def test_no_git_directory_is_all_none(self):
        self.assertEqual(system_info.git_info(self.root), {"branch": None, "commit": None, "detached": False})

    def test_garbage_head_is_ignored(self):
        self.git("ref: refs/heads/main\n", {"main": "not-a-sha"})
        self.assertIsNone(system_info.git_info(self.root)["commit"])


if __name__ == "__main__":
    unittest.main()
