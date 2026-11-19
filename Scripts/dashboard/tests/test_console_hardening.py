"""Server behaviour added in the dashboard review: JSON/secure error responses, favicon, quality-gate endpoint,
cross-site POST refusal, HSTS behind HTTPS."""
import base64
import json
import os
import shutil
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds
from wizard.api import WizardApi

REAL_ROOT = os.path.abspath(os.path.join(h.DASH, "..", ".."))
PW = "console-test-password"


class ConsoleHardening(unittest.TestCase):
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
                           "DASHBOARD_SECRETS_FILE": os.path.join(root, "s.json")})
        cls._saved = (ds.REPO_ROOT, ds.PIPELINE_CONFIG, ds.TEST_CONFIG, ds.SUITES_DIR)
        ds.REPO_ROOT = cls.repo
        ds.PIPELINE_CONFIG = os.path.join(cls.repo, "pipeline-config.properties")
        ds.TEST_CONFIG = os.path.join(cls.repo, "test-config.properties")
        ds.SUITES_DIR = os.path.join(cls.repo, "testng-suites")
        cls.wizard = WizardApi(cls.repo, ds.PIPELINE_CONFIG, ds.SUITES_DIR)
        handler = ds.make_handler(PW, cls.wizard)
        handler.log_message = lambda self, fmt, *args: None
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.wizard.runner.queue.shutdown()
        ds.REPO_ROOT, ds.PIPELINE_CONFIG, ds.TEST_CONFIG, ds.SUITES_DIR = cls._saved
        os.environ.clear()
        os.environ.update(cls._env)
        cls._t.cleanup()

    def call(self, method, path, body=None, headers=None):
        auth = "Basic " + base64.b64encode(f"dashboard:{PW}".encode()).decode()
        hdrs = {"Authorization": auth, **(headers or {})}
        data = json.dumps(body).encode() if body is not None else None
        if data is not None:
            hdrs.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_unknown_api_path_is_json_with_security_headers(self):
        status, headers, body = self.call("GET", "/api/does-not-exist")
        self.assertEqual(status, 404)
        self.assertTrue(headers["Content-Type"].startswith("application/json"))
        self.assertIn("error", json.loads(body))
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertIn("Content-Security-Policy", headers)

    def test_unknown_page_is_plain_text_with_security_headers(self):
        status, headers, body = self.call("GET", "/nope")
        self.assertEqual(status, 404)
        self.assertTrue(headers["Content-Type"].startswith("text/plain"))
        self.assertEqual(headers["X-Frame-Options"], "DENY")
        self.assertNotIn(b"<html", body.lower())

    def test_favicon_served(self):
        for path in ("/favicon.svg", "/favicon.ico"):
            status, headers, body = self.call("GET", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(headers["Content-Type"], "image/svg+xml")
            self.assertTrue(body.startswith(b"<svg"))

    def test_quality_gate_unknown_without_a_run(self):
        status, _, body = self.call("GET", "/api/quality-gate")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["status"], "unknown")

    def test_quality_gate_rejects_bad_limits(self):
        for q in ("min_pass=abc", "min_pass=101", "max_failed=-1", "min_coverage=nan"):
            status, _, _ = self.call("GET", "/api/quality-gate?" + q)
            self.assertEqual(status, 400, q)

    def test_cross_site_post_refused(self):
        status, _, _ = self.call("POST", "/api/prefs", {"theme": "dark"}, {"Sec-Fetch-Site": "cross-site"})
        self.assertEqual(status, 403)
        status, _, _ = self.call("POST", "/api/prefs", {"theme": "dark"}, {"Sec-Fetch-Site": "same-origin"})
        self.assertEqual(status, 200)

    def test_hsts_only_behind_https_proxy(self):
        _, plain, _ = self.call("GET", "/api/health")
        self.assertNotIn("Strict-Transport-Security", plain)
        _, proxied, _ = self.call("GET", "/api/health", headers={"X-Forwarded-Proto": "https"})
        self.assertIn("Strict-Transport-Security", proxied)


if __name__ == "__main__":
    unittest.main()
