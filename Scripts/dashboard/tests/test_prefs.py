"""Per-login preferences (prefs.py + /api/prefs), density/phone-layout hooks and the standalone HTML report."""
import base64
import http.client
import json
import os
import re
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds
import prefs
from auth import users as auth_users

PW = "prefs-test-password-1"


class PrefsStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["DASHBOARD_DATA_DIR"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("DASHBOARD_DATA_DIR", None))

    def test_defaults_when_nothing_is_stored(self):
        self.assertEqual(prefs.get("alice"), prefs.DEFAULTS)
        self.assertEqual(prefs.describe("alice")["saved"], [])

    def test_update_merges_and_persists_per_user(self):
        prefs.update("alice", {"theme": "dark"})
        out = prefs.update("alice", {"density": "compact", "refresh_s": 0})
        self.assertEqual((out["theme"], out["density"], out["refresh_s"]), ("dark", "compact", 0))
        self.assertEqual(prefs.get("bob"), prefs.DEFAULTS, "another login must not inherit it")
        self.assertEqual(prefs.describe("alice")["saved"], ["density", "refresh_s", "theme"])

    def test_invalid_input_is_rejected(self):
        for patch in ({}, {"theme": "purple"}, {"unknown": 1}, {"refresh_s": 7}, {"refresh_s": "15"}, {"refresh_s": True},
                      {"refresh_s": False}, {"default_tab": "../etc"}, {"theme": ["dark"]}, "x", None):
            with self.subTest(patch=patch):
                with self.assertRaises(ValueError):
                    prefs.update("alice", patch)
        self.assertEqual(prefs.get("alice"), prefs.DEFAULTS, "a rejected update must change nothing")

    def test_user_names_are_bounded(self):
        for bad in ("", "x" * 65, None, 5):
            with self.subTest(user=bad):
                with self.assertRaises(ValueError):
                    prefs.update(bad, {"theme": "dark"})

    def test_number_of_stored_users_is_capped(self):
        for i in range(prefs.MAX_USERS):
            prefs.update(f"user{i}", {"theme": "dark"})
        with self.assertRaises(ValueError):
            prefs.update("one-too-many", {"theme": "dark"})
        prefs.update("user0", {"theme": "light"})      # existing users can still change theirs

    def test_damaged_or_hand_edited_file_never_reaches_the_ui(self):
        with open(prefs.prefs_path(), "w") as f:
            json.dump({"alice": {"theme": "<script>", "density": "compact", "refresh_s": 9999, "extra": 1}, "bob": "nope"}, f)
        out = prefs.get("alice")
        self.assertEqual((out["theme"], out["density"], out["refresh_s"]), ("system", "compact", 15))
        self.assertEqual(prefs.get("bob"), prefs.DEFAULTS)
        with open(prefs.prefs_path(), "w") as f:
            f.write("{not json")
        self.assertEqual(prefs.get("alice"), prefs.DEFAULTS)
        prefs.update("alice", {"theme": "light"})       # and it recovers on the next save
        self.assertEqual(prefs.get("alice")["theme"], "light")

    @unittest.skipIf(os.name == "nt", "POSIX file modes")
    def test_file_is_private(self):
        prefs.update("alice", {"theme": "dark"})
        self.assertEqual(os.stat(prefs.prefs_path()).st_mode & 0o777, 0o600)


class PrefsHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._t = h.tmpdir()
        root = cls._t.name
        cls._env = dict(os.environ)
        os.environ.update({"DASHBOARD_DATA_DIR": os.path.join(root, "data"),
                           "DASHBOARD_USERS_FILE": os.path.join(root, "users.json"), "DASHBOARD_RUNNER_ENABLED": ""})
        auth_users.add_user("tester1", "tester", "tester-password-1")
        auth_users.add_user("lead1", "lead", "lead-password-1234")
        handler = ds.make_handler(None, None)
        handler.log_message = lambda self, fmt, *args: None
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        os.environ.clear()
        os.environ.update(cls._env)
        cls._t.cleanup()

    @staticmethod
    def basic(user, pw):
        return "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode()

    def call(self, method, path, user=("tester1", "tester-password-1"), body=None, ctype="application/json"):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        headers = {}
        if user:
            headers["Authorization"] = self.basic(*user)
        data = None
        if body is not None:
            data = body if isinstance(body, (bytes, str)) else json.dumps(body)
            headers["Content-Type"] = ctype
        conn.request(method, path, body=data, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        try:
            return resp.status, json.loads(raw)
        except ValueError:
            return resp.status, raw

    def test_requires_authentication(self):
        self.assertEqual(self.call("GET", "/api/prefs", user=None)[0], 401)
        self.assertEqual(self.call("POST", "/api/prefs", user=None, body={"theme": "dark"})[0], 401)

    def test_a_tester_may_change_their_own_preferences_but_not_the_configuration(self):
        status, out = self.call("POST", "/api/prefs", body={"theme": "light", "density": "compact"})
        self.assertEqual(status, 200, out)
        self.assertEqual((out["theme"], out["density"]), ("light", "compact"))
        # config edits are still gated (tester < lead) — the prefs route must not have opened that door
        self.assertEqual(self.call("POST", "/api/pipeline-config/site", body={"name": "demoqa", "enabled": True})[0], 403)

    def test_each_login_has_its_own_preferences(self):
        self.call("POST", "/api/prefs", body={"default_tab": "results"})
        mine = self.call("GET", "/api/prefs")[1]
        theirs = self.call("GET", "/api/prefs", user=("lead1", "lead-password-1234"))[1]
        self.assertEqual(mine["default_tab"], "results")
        self.assertEqual(theirs["default_tab"], "overview")
        self.assertEqual(theirs["saved"], [])
        self.assertIn("default_tab", mine["saved"])

    def test_bad_values_are_a_400_and_change_nothing(self):
        before = self.call("GET", "/api/prefs")[1]
        for body in ({"theme": "neon"}, {"refresh_s": 1}, {"nope": 1}, {}):
            with self.subTest(body=body):
                self.assertEqual(self.call("POST", "/api/prefs", body=body)[0], 400)
        self.assertEqual(self.call("GET", "/api/prefs")[1], before)

    def test_cross_site_style_posts_are_refused(self):
        self.assertEqual(self.call("POST", "/api/prefs", body=json.dumps({"theme": "dark"}), ctype="text/plain")[0], 403)


class WiringTests(unittest.TestCase):
    def test_preference_controls_exist_and_match_the_server_choices(self):
        html = h.read(os.path.join(h.DASH, "static", "index.html"))
        for key, choices in prefs.CHOICES.items():
            sel = re.search(rf'<select id="[^"]+" data-pref="{key}">(.*?)</select>', html, re.S)
            self.assertIsNotNone(sel, key)
            self.assertEqual(sorted(re.findall(r'value="([^"]+)"', sel.group(1))), sorted(str(c) for c in choices), key)

    def test_density_phone_and_report_hooks_exist(self):
        css = h.read(os.path.join(h.DASH, "static", "style.css"))
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        html = h.read(os.path.join(h.DASH, "static", "index.html"))
        self.assertIn(':root[data-density="compact"]', css)
        self.assertRegex(css, r"@media \(max-width: 640px\)[^{]*\{\s*\.topbar-inner[^}]*\}\s*\.tabs\s*\{[^}]*position: fixed")
        for needle in ("data-export-report", 'id="prefsBtn"', 'id="prefsModal"'):
            self.assertIn(needle, html)
        for needle in ("function buildReportHtml", "function exportReportHtml", "function initPrefs", '"/api/prefs"'):
            self.assertIn(needle, app)
        self.assertNotIn(" style=", html)

    def test_report_builder_never_writes_test_output_as_markup(self):
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        body = app[app.index("function buildReportHtml"):app.index("function downloadText")]
        for field in ("c.label", "c.type", "first.message", "f.name", "t.test", "r.name", "c.class"):
            for m in re.finditer(r"\$\{([^}]*" + re.escape(field) + r"[^}]*)\}", body):
                self.assertIn("e(", m.group(1), f"{field} reaches the report without escaping: {m.group(0)}")


if __name__ == "__main__":
    unittest.main()
