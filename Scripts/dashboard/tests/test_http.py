import base64
import json
import os
import shutil
import stat
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds
from auth import users as au
from wizard.api import WizardApi

REAL_ROOT = os.path.abspath(os.path.join(h.DASH, "..", ".."))


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._t = h.tmpdir()
        root = cls._t.name
        cls.repo = os.path.join(root, "repo")
        os.makedirs(cls.repo)
        for f in ("pipeline-config.properties", "test-config.properties"):
            shutil.copy(os.path.join(REAL_ROOT, f), cls.repo)
        shutil.copytree(os.path.join(REAL_ROOT, "testng-suites"), os.path.join(cls.repo, "testng-suites"))
        bindir = os.path.join(root, "bin")
        os.makedirs(bindir)
        mvn = os.path.join(bindir, "mvn")
        h.write(mvn, "#!/bin/sh\necho ran $@\nexit 0\n")
        os.chmod(mvn, os.stat(mvn).st_mode | stat.S_IEXEC)
        cls._env = dict(os.environ)
        os.environ.update({"PATH": bindir + os.pathsep + os.environ["PATH"], "DASHBOARD_DATA_DIR": os.path.join(root, "data"),
                           "DASHBOARD_USERS_FILE": os.path.join(root, "users.json"), "DASHBOARD_RUNNER_ENABLED": "1",
                           "DASHBOARD_SECRETS_FILE": os.path.join(root, "s.json")})
        for k in ("DASHBOARD_SECRET_KEY",):
            os.environ.pop(k, None)
        au.add_user("tess", "tester", "tester-password-1")
        au.add_user("lena", "lead", "lead-password-12")
        au.add_user("adam", "admin", "admin-password-1")
        cls._saved = (ds.REPO_ROOT, ds.PIPELINE_CONFIG, ds.TEST_CONFIG, ds.SUITES_DIR)
        ds.REPO_ROOT = cls.repo
        ds.PIPELINE_CONFIG = os.path.join(cls.repo, "pipeline-config.properties")
        ds.TEST_CONFIG = os.path.join(cls.repo, "test-config.properties")
        ds.SUITES_DIR = os.path.join(cls.repo, "testng-suites")
        cls.wizard = WizardApi(cls.repo, ds.PIPELINE_CONFIG, ds.SUITES_DIR)
        handler = ds.make_handler("legacy-shared-pw", cls.wizard)
        handler.log_message = lambda self, fmt, *args: None  # keep test output free of per-request log lines
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

    def call(self, method, path, body=None, user="tess", pw="tester-password-1", headers=None, raw=False):
        req = urllib.request.Request(self.base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
        if user:
            req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode())
        if method == "POST":
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req) as r:
                data = r.read()
                return r.status, (data if raw else json.loads(data)), dict(r.headers)
        except urllib.error.HTTPError as e:
            with e:  # closes the error response's underlying file
                data = e.read()
                try:
                    return e.code, json.loads(data), dict(e.headers)
                except ValueError:
                    return e.code, data, dict(e.headers)

    def _post_raw(self, path, data, user="adam", pw="admin-password-1"):
        """POST an arbitrary (possibly invalid / oversized) body with a JSON content type."""
        req = urllib.request.Request(self.base + path, method="POST", data=data)
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode())
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    # ── request-body handling (a rejected body must never be treated as an empty one) ──
    def test_oversized_body_is_rejected_and_changes_nothing(self):
        self.assertEqual(self.call("POST", "/api/test-config/run-only", {"value": "LoginTest"}, user="adam", pw="admin-password-1")[0], 200)
        status, data = self._post_raw("/api/test-config/run-only", b'{"value": "' + b"a" * 1_100_000 + b'"}')
        self.assertEqual(status, 413)
        self.assertIn("too large", data["error"])
        s, cfg, _ = self.call("GET", "/api/test-config", user="adam", pw="admin-password-1")
        self.assertEqual(cfg["run_only"], "LoginTest")

    def test_invalid_json_and_non_object_bodies_are_400(self):
        for raw in (b"{not json", b"[1, 2]", b'"text"'):
            status, data = self._post_raw("/api/test-config/run-only", raw)
            self.assertEqual(status, 400, raw)
            self.assertIn("error", data)

    def test_empty_snapshot_import_is_rejected_and_keeps_run_only(self):
        self.call("POST", "/api/test-config/run-only", {"value": "LoginTest"}, user="adam", pw="admin-password-1")
        status, data = self._post_raw("/api/snapshot/import", b"{}")
        self.assertEqual(status, 400)
        self.assertIn("not a dashboard snapshot", data["error"])
        status, data = self._post_raw("/api/snapshot/import", b"x" * 1_100_000)
        self.assertEqual(status, 413)
        s, cfg, _ = self.call("GET", "/api/test-config", user="adam", pw="admin-password-1")
        self.assertEqual(cfg["run_only"], "LoginTest")

    # ── auth ──
    def test_requires_auth_and_rejects_bad_password(self):
        self.assertEqual(self.call("GET", "/api/wizard/options", user=None)[0], 401)
        self.assertEqual(self.call("GET", "/api/wizard/options", pw="nope-nope-nope")[0], 401)

    def test_legacy_login_is_admin(self):
        s, d, _ = self.call("GET", "/api/wizard/options", user="dashboard", pw="legacy-shared-pw")
        self.assertEqual((s, d["role"], d["user"]), (200, "admin", "dashboard"))

    def test_user_cannot_use_legacy_name_with_users_file(self):
        self.assertEqual(self.call("GET", "/api/wizard/options", user="dashboard", pw="tester-password-1")[0], 401)

    def test_options_content(self):
        s, d, _ = self.call("GET", "/api/wizard/options")
        self.assertEqual(s, 200)
        self.assertEqual([t["type"] for t in d["types"]], ["ui", "api", "openapi", "performance", "crawler", "accessibility", "mobile"])
        self.assertIn("saucedemo", d["sites"])
        self.assertIn("demoqa", d["sitesFor"]["perf"])
        self.assertNotIn("saucedemo", d["sitesFor"]["perf"])
        self.assertTrue(d["permissions"]["run.start"])
        self.assertFalse(d["permissions"]["ci.trigger"])

    # ── static ──
    def test_wizard_page_and_assets(self):
        s, body, hdr = self.call("GET", "/wizard", raw=True)
        self.assertEqual(s, 200)
        self.assertIn(b"Test Wizard", body)
        self.assertIn("default-src 'self'", hdr["Content-Security-Policy"])
        self.assertEqual(self.call("GET", "/wizard/static/wizard.js", raw=True)[0], 200)
        self.assertEqual(self.call("GET", "/wizard/static/../../dashboard_server.py", raw=True)[0], 404)
        self.assertEqual(self.call("GET", "/wizard/static/%2e%2e/dashboard_server.py", raw=True)[0], 404)

    def test_forms_endpoint(self):
        self.assertEqual(self.call("GET", "/api/wizard/forms/api")[0], 200)
        self.assertEqual(self.call("GET", "/api/wizard/forms/shell")[0], 404)

    # ── validate / templates ──
    def test_validate_endpoint(self):
        s, d, _ = self.call("POST", "/api/wizard/validate", {"spec": h.ui_spec()})
        self.assertTrue(d["ok"], d)
        bad = h.ui_spec()
        bad["target"]["baseUrl"] = "javascript:alert(1)"
        self.assertFalse(self.call("POST", "/api/wizard/validate", {"spec": bad})[1]["ok"])

    def test_template_lifecycle_and_roles(self):
        s, d, _ = self.call("POST", "/api/templates", {"spec": h.api_spec()})
        self.assertEqual((s, d["slug"], d["version"]), (200, "posts-api", 1))
        self.assertEqual(self.call("GET", "/api/templates")[1]["templates"][0]["slug"], "posts-api")
        self.assertEqual(self.call("GET", "/api/templates/posts-api")[1]["name"], "Posts API")
        self.assertEqual(self.call("POST", "/api/templates/posts-api/delete", {})[0], 403)          # tester
        self.assertEqual(self.call("POST", "/api/templates/posts-api/delete", {}, "lena", "lead-password-12")[0], 200)
        self.assertEqual(self.call("GET", "/api/templates/posts-api")[0], 404)

    def test_save_rejects_literal_secret(self):
        bad = h.api_spec()
        bad["target"]["authToken"] = "literal-token-value"
        s, d, _ = self.call("POST", "/api/templates", {"spec": bad})
        self.assertEqual(s, 400)
        self.assertNotIn("literal-token-value", json.dumps(d))

    # ── runs ──
    def test_run_flow_and_files(self):
        s, st, _ = self.call("POST", "/api/runs", {"spec": h.perf_spec()})
        self.assertEqual(s, 200, st)
        rid = st["id"]
        for _ in range(100):
            cur = self.call("GET", f"/api/runs/{rid}")[1]
            if cur["state"] in ("passed", "failed", "error"):
                break
            time.sleep(0.1)
        self.assertEqual(cur["state"], "passed", cur)
        self.assertIn(b"Run summary", self.call("GET", cur["reportLink"], raw=True)[1])
        self.assertIn("ran -B", self.call("GET", f"/api/runs/{rid}/log?offset=0")[1]["text"])
        for evil in ("../../spec.json", "inputs/suite.xml", "status.json", "report/../status.json", "..%2f..%2fetc/passwd"):
            self.assertEqual(self.call("GET", f"/api/runs/{rid}/files/{evil}", raw=True)[0], 404, evil)
        self.assertEqual(self.call("GET", "/api/runs/..%2f..%2fx/log")[0], 404)
        self.assertEqual(self.call("GET", f"/api/runs/{rid}/files/spec.json", raw=True)[0], 200)

    def test_run_rejects_invalid_spec(self):
        bad = h.perf_spec()
        bad["details"]["threads"] = 100000
        self.assertEqual(self.call("POST", "/api/runs", {"spec": bad})[0], 400)

    def test_tester_cannot_cancel_others_run(self):
        os.environ["DASHBOARD_RUN_ENV_PASSTHROUGH"] = ""
        s, st, _ = self.call("POST", "/api/runs", {"spec": h.perf_spec() | {"name": "Lena run"}}, "lena", "lead-password-12")
        self.assertEqual(self.call("POST", f"/api/runs/{st['id']}/cancel", {})[0], 403)

    # ── roles on other endpoints ──
    def test_ci_and_secrets_need_roles(self):
        self.assertEqual(self.call("POST", "/api/ci/trigger", {"provider": "github", "params": {}})[0], 403)
        self.assertEqual(self.call("GET", "/api/secrets")[0], 403)
        self.assertEqual(self.call("GET", "/api/secrets", user="lena", pw="lead-password-12")[0], 403)
        s, d, _ = self.call("GET", "/api/secrets", user="adam", pw="admin-password-1")
        self.assertEqual((s, d["available"]), (200, False))        # no DASHBOARD_SECRET_KEY set here
        self.assertEqual(self.call("POST", "/api/secrets", {"name": "A", "value": "b"}, "adam", "admin-password-1")[0], 409)
        self.assertEqual(self.call("POST", "/api/ci/trigger", {"provider": "github", "params": {}}, "lena", "lead-password-12")[0], 502)

    def test_existing_config_endpoints_respect_roles_in_per_user_mode(self):
        body = {"name": "demoqa", "enabled": True}
        self.assertEqual(self.call("POST", "/api/pipeline-config/site", body)[0], 403)              # tester
        self.assertEqual(self.call("POST", "/api/pipeline-config/site", body, "lena", "lead-password-12")[0], 200)
        self.assertEqual(self.call("GET", "/api/pipeline-config")[0], 200)                          # reading is open to all

    def test_csrf_still_enforced_on_wizard_posts(self):
        req = urllib.request.Request(self.base + "/api/runs", method="POST", data=b"{}")
        req.add_header("Authorization", "Basic " + base64.b64encode(b"tess:tester-password-1").decode())
        req.add_header("Content-Type", "text/plain")
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(req)
        cm.exception.close()
        self.assertEqual(cm.exception.code, 403)
        s, _, _ = self.call("POST", "/api/runs", {"spec": h.perf_spec()}, headers={"Origin": "http://evil.example"})
        self.assertEqual(s, 403)

    # ── audit separation ──
    def test_wizard_audit_does_not_block_config_undo(self):
        self.call("POST", "/api/pipeline-config/site", {"name": "demoqa", "enabled": False}, "lena", "lead-password-12")
        self.call("POST", "/api/templates", {"spec": h.ui_spec()})
        s, d, _ = self.call("POST", "/api/audit/undo", {}, "lena", "lead-password-12")
        self.assertEqual(s, 200, d)
        self.assertTrue(os.path.isfile(os.path.join(self.repo, "target", "dashboard-wizard-audit.jsonl")))
        log = h.read(os.path.join(self.repo, "target", "dashboard-wizard-audit.jsonl"))
        self.assertIn("template-save", log)
        self.assertNotIn("tester-password-1", log)

    # ── regressions from the code review ────────────────────────────────
    def _admin(self, method, path, body=None, **kw):
        return self.call(method, path, body, user="adam", pw="admin-password-1", **kw)

    def test_unknown_site_is_refused_and_nothing_is_written(self):
        before = _read(ds.PIPELINE_CONFIG)
        status, body, _ = self._admin("POST", "/api/pipeline-config/site", {"name": "ghostsite", "enabled": True})
        self.assertEqual(status, 400)
        self.assertIn("unknown site", body["error"])
        self.assertEqual(_read(ds.PIPELINE_CONFIG), before)

    def test_unknown_or_oversized_test_name_is_refused(self):
        before = _read(ds.TEST_CONFIG)
        for name in ("NoSuchTest", "../x", "x" * 500):
            status, _, _ = self._admin("POST", "/api/test-config/test", {"name": name, "enabled": True})
            self.assertEqual(status, 400, name[:20])
        self.assertEqual(_read(ds.TEST_CONFIG), before)

    def test_known_test_can_still_be_toggled(self):
        _, cfg, _ = self._admin("GET", "/api/test-config")
        t = cfg["sections"][0]["tests"][0]
        status, _, _ = self._admin("POST", "/api/test-config/test", {"name": t["name"], "enabled": not t["enabled"]})
        self.assertEqual(status, 200)
        self._admin("POST", "/api/test-config/test", {"name": t["name"], "enabled": t["enabled"]})

    def test_head_health_and_405_for_other_methods(self):
        req = urllib.request.Request(self.base + "/api/health", method="HEAD")
        req.add_header("Authorization", "Basic " + base64.b64encode(b"tess:tester-password-1").decode())
        with urllib.request.urlopen(req) as r:
            self.assertEqual(r.status, 200)
            self.assertEqual(r.read(), b"")
        for method in ("PUT", "DELETE", "PATCH"):
            status, _, headers = self._admin(method, "/api/health")
            self.assertEqual(status, 405, method)
            self.assertIn("POST", headers.get("Allow", ""))
        status, _, _ = self.call("PUT", "/api/health", user=None)
        self.assertEqual(status, 401)  # unauthenticated callers learn nothing about the methods

    def test_results_api_includes_server_side_history(self):
        status, body, _ = self.call("GET", "/api/results")
        self.assertEqual(status, 200)
        self.assertIsInstance(body["history"], list)

    def test_listen_backlog_is_larger_than_the_stdlib_default(self):
        self.assertGreaterEqual(ds.DashboardServer.request_queue_size, 64)


class DisabledRunnerTest(unittest.TestCase):
    def test_runs_refused_when_switch_is_off(self):
        os.environ.pop("DASHBOARD_RUNNER_ENABLED", None)
        tmp = h.tmpdir()
        self.addCleanup(tmp.cleanup)
        os.environ["DASHBOARD_DATA_DIR"] = tmp.name
        api = WizardApi(os.path.join(h.DASH, "..", ".."), os.path.join(REAL_ROOT, "pipeline-config.properties"), os.path.join(REAL_ROOT, "testng-suites"))
        try:
            status, body = api.handle("POST", "/api/runs", {}, {"spec": h.perf_spec()}, {"name": "x", "role": "admin"})
            self.assertEqual(status, 403)
            self.assertIn("switched off", body["error"])
        finally:
            api.runner.queue.shutdown()


if __name__ == "__main__":
    unittest.main()
