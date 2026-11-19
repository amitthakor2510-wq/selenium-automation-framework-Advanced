"""Run-time history, run comparison, per-test history, environment health, alert thresholds, and the wiring of
the Console panels that use them."""
import base64
import http.client
import http.server
import json
import os
import re
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds
import dep_health
import prefs
import results_history as rh
import run_insights as ri

SF = {"total": 4, "passed": 2, "failed": 2, "skipped": 0, "failures": ["A.x", "A.y"]}
DETAILS = {"duration_s": 61.234,
           "slowest": [{"class": "A", "name": "x", "seconds": 30.5}, {"class": "B", "name": "z", "seconds": 4.0}],
           "failure_details": [{"class": "A", "name": "x", "message": "boom " + "!" * 400},
                               {"class": "A", "name": "y", "message": "bang"}]}


def run(t, fails, dur=None, slow=None, msgs=None):
    e = {"t": t, "sig": str(t), "rate": 50.0, "total": 4, "passed": 4 - len(fails), "failed": len(fails), "fails": list(fails)}
    if dur is not None:
        e["dur"] = dur
    if slow is not None:
        e["slow"] = slow
    if msgs is not None:
        e["msgs"] = msgs
    return e


class HistoryDetailsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["DASHBOARD_DATA_DIR"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("DASHBOARD_DATA_DIR", None))
        self.repo = tempfile.mkdtemp()

    def test_record_keeps_duration_slowest_and_trimmed_messages(self):
        e = rh.record(self.repo, SF, DETAILS, now=1000)[-1]
        self.assertEqual(e["dur"], 61.2)
        self.assertEqual(e["slow"][0], ["A.x", 30.5])
        self.assertLessEqual(len(e["msgs"]["A.x"]), rh.MAX_MSG_LEN)
        self.assertEqual(e["msgs"]["A.y"], "bang")

    def test_a_run_recorded_before_this_feature_is_filled_in_once(self):
        rh.record(self.repo, SF, None, now=1000)
        self.assertNotIn("slow", rh.read()[-1])
        rh.record(self.repo, SF, DETAILS, now=1000)
        self.assertEqual(len(rh.read()), 1, "same run: enriched, not duplicated")
        self.assertIn("slow", rh.read()[-1])

    def test_garbage_optional_fields_are_dropped_on_read(self):
        bad = {"t": 5, "sig": "s", "rate": 10, "dur": "fast", "slow": [["ok", 1], ["bad"], [3, 4], ["neg", -1]],
               "msgs": {"a": "x", "b": 5}}
        with open(rh.history_path(), "w") as f:
            json.dump([bad, {"t": 6, "sig": "s2", "rate": 10, "slow": "no", "msgs": []}], f)
        a, b = rh.read()
        self.assertNotIn("dur", a)
        self.assertEqual(a["slow"], [["ok", 1]])
        self.assertEqual(a["msgs"], {"a": "x"})
        self.assertEqual((b["slow"], b["msgs"]), ([], {}))


class CompareTests(unittest.TestCase):
    H = [run(1, ["A.x", "A.old"], dur=100, slow=[["A.x", 5.0], ["B.z", 3.0]]),
         run(2, ["A.x", "A.new"], dur=150, slow=[["A.x", 5.5], ["B.z", 9.0]], msgs={"A.new": "NPE"})]

    def test_new_fixed_still_failing_and_slower(self):
        r = ri.compare(self.H, 1, 2)
        self.assertTrue(r["per_test"])
        self.assertEqual([x["test"] for x in r["new_failures"]], ["A.new"])
        self.assertEqual(r["new_failures"][0]["message"], "NPE")
        self.assertEqual(r["fixed"], ["A.old"])
        self.assertEqual(r["still_failing"], ["A.x"])
        self.assertEqual([x["test"] for x in r["slower"]], ["B.z"], "A.x moved by 0.5s: not slower")
        self.assertEqual(r["slower"][0]["delta"], 6.0)
        self.assertEqual(r["duration_pct"], 50.0)

    def test_old_entries_without_per_test_data_still_compare_totals(self):
        r = ri.compare([{"t": 1, "sig": "a", "rate": 90, "total": 10, "failed": 1}, self.H[1]], 1, 2)
        self.assertFalse(r["per_test"])
        self.assertEqual(r["new_failures"], [])

    def test_bad_requests(self):
        for a, b in ((1, 1), (1, 99), ("1", 2), (None, 2), (True, 2)):
            with self.subTest(a=a, b=b):
                if a is True:   # bool is an int in Python; it must not select run t=1 by accident
                    a = 99
                with self.assertRaises(ValueError):
                    ri.compare(self.H, a, b)


class TestHistoryTests(unittest.TestCase):
    H = [run(1, ["A.x"], slow=[["A.x", 4.0]], msgs={"A.x": "first"}), run(2, []), run(3, ["A.x"], slow=[["A.x", 8.0]], msgs={"A.x": "latest"}),
         {"t": 0, "sig": "old", "rate": 50}]

    def test_history_stats_and_last_error(self):
        r = ri.test_history(self.H, "A.x")
        self.assertEqual([x["status"] for x in r["runs"]], ["fail", "pass", "fail"], "the entry without per-test data is skipped")
        self.assertEqual((r["fails"], r["total_runs"], r["flips"], r["kind"]), (2, 3, 2, "flaky"))
        self.assertEqual(r["last_error"], "latest")
        self.assertEqual((r["avg_seconds"], r["max_seconds"], r["timed_runs"]), (6.0, 8.0, 2))

    def test_unknown_test_is_reported_as_not_known_not_as_an_error(self):
        r = ri.test_history(self.H, "Nope.nothing")
        self.assertFalse(r["known"])
        self.assertIsNone(r["kind"])

    def test_bad_names(self):
        for bad in ("", "  ", None, 5, "x" * 301):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                ri.test_history(self.H, bad)


class _OkHandler(http.server.BaseHTTPRequestHandler):
    seen = []

    def do_GET(self):
        _OkHandler.seen.append((self.path, self.headers.get("Authorization")))
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


class DepHealthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = http.server.HTTPServer(("127.0.0.1", 0), _OkHandler)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def repo(self, grid="", ollama="", rp="", appium="", grid_enabled="true"):
        d = tempfile.mkdtemp()
        res = os.path.join(d, "src", "test", "resources")
        os.makedirs(os.path.join(res, "config"))
        with open(os.path.join(res, "config", "global.properties"), "w") as f:
            f.write(f"grid.enabled={grid_enabled}\ngrid.url={grid}\nai.endpoint={ollama}\n")
        with open(os.path.join(res, "reportportal.properties"), "w") as f:
            f.write(f"rp.endpoint={rp}\n")
        with open(os.path.join(res, "config", "mobile.properties"), "w") as f:
            f.write(f"appium.server.url={appium}\n")
        return d

    def by_id(self, repo):
        return {t["id"]: t for t in dep_health.check(repo, now=1e9 + hash(repo) % 1000)["targets"]}

    def test_reachable_unreachable_and_unconfigured(self):
        closed = self.port + 1 if self.port < 65000 else self.port - 1
        r = self.by_id(self.repo(grid=f"http://127.0.0.1:{self.port}/wd/hub", ollama=f"http://127.0.0.1:{closed}/api/chat"))
        self.assertTrue(r["grid"]["reachable"])
        self.assertEqual(r["grid"]["status"], 200)
        self.assertEqual(r["grid"]["target"], f"127.0.0.1:{self.port}")
        self.assertIs(r["ollama"]["reachable"], False)
        self.assertFalse(r["reportportal"]["configured"])
        self.assertIsNone(r["reportportal"]["reachable"])
        self.assertIn("/status", [p for p, _ in _OkHandler.seen], "a FIXED health path is requested, not the configured one")
        self.assertNotIn("/wd/hub", [p for p, _ in _OkHandler.seen])

    def test_credentials_in_a_configured_url_are_neither_sent_nor_shown(self):
        _OkHandler.seen.clear()
        r = self.by_id(self.repo(appium=f"http://admin:s3cret@127.0.0.1:{self.port}/x?token=abc"))["appium"]
        self.assertTrue(r["reachable"])
        self.assertNotIn("s3cret", json.dumps(r))
        self.assertNotIn("token", json.dumps(r))
        self.assertTrue(all(auth is None for _, auth in _OkHandler.seen))

    def test_unsafe_targets_are_refused_without_a_connection(self):
        r = self.by_id(self.repo(grid="http://169.254.169.254/latest", ollama="file:///etc/passwd", rp="ftp://example.com",
                                 appium="http://0.0.0.0:4723"))
        self.assertIs(r["grid"]["reachable"], None)
        self.assertIn("not allowed", r["grid"]["note"])
        self.assertIn("not a plain http(s) URL", r["ollama"]["note"])
        self.assertIn("not a plain http(s) URL", r["reportportal"]["note"])
        self.assertIn("not allowed", r["appium"]["note"])

    def test_emulator_is_checked_with_a_plain_tcp_connect_and_nothing_is_sent(self):
        import socket
        got = []
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]

        def accept():
            c, _ = srv.accept()
            c.settimeout(0.5)
            try:
                got.append(c.recv(10))
            except OSError:
                got.append(b"")
            c.close()
        th = threading.Thread(target=accept, daemon=True)
        th.start()
        repo = self.repo()
        with open(os.path.join(repo, "src", "test", "resources", "config", "mobile.properties"), "w") as f:
            f.write(f"appium.server.url=\nmobile.device.name=127.0.0.1:{port}\n")
        r = self.by_id(repo)["emulator"]
        th.join(3)
        srv.close()
        self.assertTrue(r["reachable"], r)
        self.assertEqual(r["target"], f"127.0.0.1:{port}")
        self.assertEqual(got, [b""], "the connection is opened and closed; no bytes are sent")

    def test_emulator_name_forms(self):
        self.assertEqual(dep_health._device_address("emulator-5554"), ("127.0.0.1", 5554))
        self.assertEqual(dep_health._device_address("127.0.0.1:6562"), ("127.0.0.1", 6562))
        for bad in ("Samsung Galaxy S24", "emulator-12", "host:99999", "a b:5555", "http://x:80", "", "host:0"):
            with self.subTest(bad=bad):
                self.assertIsNone(dep_health._device_address(bad))

    def test_emulator_plain_name_and_unsafe_addresses_are_reported_not_probed(self):
        repo = self.repo()
        mp = os.path.join(repo, "src", "test", "resources", "config", "mobile.properties")
        for value, expect in (("Samsung Galaxy S24", "device name only"), ("169.254.169.254:5555", "not allowed"), ("", "not configured")):
            with self.subTest(value=value):
                with open(mp, "w") as f:
                    f.write(f"mobile.device.name={value}\n")
                dep_health._cache.clear()
                r = self.by_id(repo)["emulator"]
                self.assertIsNone(r["reachable"])
                self.assertIn(expect, r["note"])

    def test_results_are_cached_briefly(self):
        repo = self.repo(grid=f"http://127.0.0.1:{self.port}")
        a = dep_health.check(repo, now=5000.0)
        b = dep_health.check(repo, now=5001.0)
        c = dep_health.check(repo, now=5000.0 + dep_health.CACHE_S + 1)
        self.assertIs(a, b)
        self.assertIsNot(a, c)


class AlertPrefsTests(unittest.TestCase):
    def setUp(self):
        os.environ["DASHBOARD_DATA_DIR"] = tempfile.mkdtemp()
        self.addCleanup(lambda: os.environ.pop("DASHBOARD_DATA_DIR", None))

    def test_thresholds_are_validated_and_default_off(self):
        self.assertEqual((prefs.get("a")["alert_pass_pct"], prefs.get("a")["alert_coverage_pct"], prefs.get("a")["alert_slowdown_pct"]), (0, 0, 0))
        out = prefs.update("a", {"alert_pass_pct": 90, "alert_coverage_pct": 70, "alert_slowdown_pct": 50})
        self.assertEqual((out["alert_pass_pct"], out["alert_coverage_pct"], out["alert_slowdown_pct"]), (90, 70, 50))
        for patch in ({"alert_pass_pct": 91}, {"alert_pass_pct": "90"}, {"alert_slowdown_pct": True}, {"alert_coverage_pct": -1}):
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                prefs.update("a", patch)


class EndpointTests(unittest.TestCase):
    PW = "insights-test-password-1"

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls._env = dict(os.environ)
        os.environ["DASHBOARD_DATA_DIR"] = cls.tmp
        with open(os.path.join(cls.tmp, rh.FILE_NAME), "w") as f:
            json.dump([run(10, ["A.x"], dur=10, slow=[["A.x", 5.0]]), run(20, ["A.x", "A.n"], dur=40, slow=[["A.x", 9.0]], msgs={"A.n": "NPE"})], f)
        handler = ds.make_handler(cls.PW, None)
        handler.log_message = lambda self, fmt, *args: None
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.auth = "Basic " + base64.b64encode(f"dashboard:{cls.PW}".encode()).decode()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        os.environ.clear()
        os.environ.update(cls._env)

    def get(self, path, auth=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", path, headers={"Authorization": self.auth} if auth else {})
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        try:
            return resp.status, json.loads(raw)
        except ValueError:
            return resp.status, raw

    def test_compare_endpoint(self):
        s, body = self.get("/api/results/compare?a=10&b=20")
        self.assertEqual(s, 200)
        self.assertEqual([x["test"] for x in body["new_failures"]], ["A.n"])
        self.assertEqual(body["duration_pct"], 300.0)

    def test_compare_endpoint_rejects_bad_input_with_400_not_500(self):
        for q in ("a=10", "a=x&b=20", "a=10&b=10", "a=10&b=999", "a=1e3&b=20", "a=&b="):
            with self.subTest(q=q):
                self.assertEqual(self.get(f"/api/results/compare?{q}")[0], 400)

    def test_test_history_endpoint(self):
        s, body = self.get("/api/results/test?name=A.x")
        self.assertEqual((s, body["fails"], body["total_runs"], body["max_seconds"]), (200, 2, 2, 9.0))
        self.assertEqual(self.get("/api/results/test")[0], 400)

    def test_new_endpoints_require_authentication(self):
        for p in ("/api/results/compare?a=10&b=20", "/api/results/test?name=A.x", "/api/health/deps"):
            with self.subTest(path=p):
                self.assertEqual(self.get(p, auth=False)[0], 401)

    def test_health_endpoint_shape(self):
        s, body = self.get("/api/health/deps")
        self.assertEqual(s, 200)
        self.assertEqual(sorted(t["id"] for t in body["targets"]), ["appium", "emulator", "grid", "ollama", "reportportal"])


class WiringTests(unittest.TestCase):
    def test_every_id_the_new_code_looks_up_exists_in_the_page(self):
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        html = h.read(os.path.join(h.DASH, "static", "index.html"))
        ids = set(re.findall(r'\$\("#([A-Za-z][\w-]*)"\)', app))
        missing = sorted(i for i in ids if f'id="{i}"' not in html and i not in {"resultsTable"} and not re.search(rf"id\s*=\s*[\"']{i}[\"']", app))
        self.assertEqual(missing, [], "app.js looks up ids that are not in index.html")

    def test_new_panels_exist(self):
        html = h.read(os.path.join(h.DASH, "static", "index.html"))
        for i in ("livePanel", "durationPanel", "durChart", "healthPanel", "healthBtn", "comparePanel", "cmpBtn", "viewsSelect",
                  "viewSave", "viewLink", "testModal", "testQuarantine", "alertsBanner", "alertsDismiss"):
            self.assertIn(f'id="{i}"', html)


if __name__ == "__main__":
    unittest.main()
