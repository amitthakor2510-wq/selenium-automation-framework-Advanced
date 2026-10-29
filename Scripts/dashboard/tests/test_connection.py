import socket
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from tests import _helpers as h
from wizard import connection


class _H(BaseHTTPRequestHandler):
    def _go(self):
        code = {"/ok": 200, "/missing": 404, "/login": 401, "/boom": 500}.get(self.path)
        if self.path == "/moved":
            self.send_response(302)
            self.send_header("Location", "/ok")
        elif self.path == "/nohead" and self.command == "HEAD":
            self.send_response(405)
        elif self.path == "/nohead":
            self.send_response(200)
        else:
            self.send_response(code or 200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_HEAD = _go

    def log_message(self, *a):
        pass


class ConnectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = HTTPServer(("127.0.0.1", 0), _H)
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def test_ok_and_codes(self):
        r = connection.check(self.base + "/ok")
        self.assertTrue(r["ok"])
        self.assertEqual((r["level"], r["status"]), ("good", 200))
        self.assertEqual(connection.check(self.base + "/missing")["level"], "warn")
        self.assertIn("login", connection.check(self.base + "/login")["message"])
        self.assertIn("error (500)", connection.check(self.base + "/boom")["message"])

    def test_redirect_is_reported_not_followed(self):
        r = connection.check(self.base + "/moved")
        self.assertEqual(r["status"], 302)
        self.assertEqual(r["redirect"], self.base + "/ok")

    def test_head_not_allowed_falls_back_to_get(self):
        self.assertEqual(connection.check(self.base + "/nohead")["status"], 200)

    def test_connection_refused(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        r = connection.check(f"http://127.0.0.1:{port}/")
        self.assertFalse(r["ok"])
        self.assertIn("Nothing is listening", r["message"])

    def test_bad_input_never_raises(self):
        for bad in ("", None, "ftp://x", "not a url", "http://user:pw@host/", "http://169.254.169.254/latest"):
            r = connection.check(bad)
            self.assertFalse(r["ok"], bad)
            self.assertEqual(r["level"], "bad")

    def test_host_resolving_to_blocked_range_is_refused(self):
        def evil(host, port, type=0):
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("169.254.169.254", port))]
        r = connection.check("http://metadata.example/", resolver=evil)
        self.assertFalse(r["ok"])
        self.assertIn("not allowed", r["message"])

    def test_unresolvable_host(self):
        def nope(host, port, type=0):
            raise socket.gaierror("no")
        self.assertIn("could not be found", connection.check("http://nope.invalid/", resolver=nope)["message"])

    def test_timeout_message(self):
        class Slow:
            def open(self, req, timeout=None):
                raise socket.timeout()
        r = connection.check(self.base + "/ok", opener=Slow())
        self.assertIn("No answer within", r["message"])


if __name__ == "__main__":
    unittest.main()
