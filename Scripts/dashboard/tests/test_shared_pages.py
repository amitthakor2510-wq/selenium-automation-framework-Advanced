"""Pins the cross-page consistency fixes: one shared theme script on every Console page, no inline
script/style (CSP), a way back to the Console, and a skip link — plus the Console features added with them
(failure clusters, next-run plan, run summary) being wired to elements that actually exist."""
import base64
import http.client
import os
import re
import threading
import unittest
from http.server import ThreadingHTTPServer

from tests import _helpers as h
import dashboard_server as ds

PW = "shared-pages-test-pw-1"

PAGES = {
    "console": os.path.join("static", "index.html"),
    "hub": os.path.join("hub", "static", "hub.html"),
    "control": os.path.join("features", "control.html"),
    "wizard": os.path.join("wizard", "static", "wizard.html"),
}


def page(name):
    return h.read(os.path.join(h.DASH, PAGES[name]))


class SharedPageTests(unittest.TestCase):
    def test_every_page_loads_the_shared_theme_script_from_head(self):
        for name in PAGES:
            with self.subTest(page=name):
                text = page(name)
                head = text.split("</head>")[0]
                self.assertIn('<script src="/theme.js"></script>', head,
                              "theme.js must be in <head> (no defer) so the theme is set before first paint")

    def test_every_page_has_a_theme_toggle_skip_link_and_main_landmark(self):
        for name in PAGES:
            with self.subTest(page=name):
                text = page(name)
                self.assertIn("data-theme-toggle", text)
                self.assertRegex(text, r'class="skip-link" href="#main"')
                self.assertRegex(text, r'<main[^>]*id="main"')

    def test_no_inline_script_style_or_handlers_on_any_page(self):
        for name in PAGES:
            with self.subTest(page=name):
                text = page(name)
                self.assertNotIn(" style=", text)
                self.assertNotRegex(text, r"<script(?![^>]*\bsrc=)[^>]*>")
                self.assertNotRegex(text, r"\son[a-z]+=")

    def test_satellite_pages_link_back_to_the_console(self):
        for name in ("hub", "control", "wizard"):
            with self.subTest(page=name):
                self.assertRegex(page(name), r'<a [^>]*href="/"[^>]*>Console</a>')

    def test_theme_is_owned_by_theme_js_only(self):
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        theme = h.read(os.path.join(h.DASH, "static", "theme.js"))
        # a second owner of the stored choice would double-toggle the button
        self.assertNotIn("dashboard-theme", app)
        self.assertIn("dashboard-theme", theme)
        self.assertNotRegex(theme, r"\beval\(|\binnerHTML\b|new Function")

    def test_new_console_features_are_wired_to_real_elements(self):
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        html = page("console")
        for element_id in re.findall(r'\$\("#(planBody|clustersPanel|clustersList|clustersSub)"\)', app):
            self.assertIn(f'id="{element_id}"', html)
        self.assertIn("data-copy-summary", html)
        self.assertIn("[data-copy-summary]", app)

    def test_failure_triage_controls_are_wired_to_real_elements(self):
        app = h.read(os.path.join(h.DASH, "static", "app.js"))
        html = page("console")
        for element_id in ("rerunBtn", "failuresFoot", "failExpandBtn", "failCollapseBtn"):
            self.assertIn(f'id="{element_id}"', html)
            self.assertIn(f'"#{element_id}"', app)
        self.assertIn("function showShortcuts", app)

    def test_header_buttons_never_depend_on_a_font_glyph_or_a_hidden_label(self):
        # Found by looking at the real phone layout: below 1000px the "Search" label is hidden and the shortcut hint
        # is hidden too, which left an EMPTY button; the theme glyph also rendered as a clipped half-moon.
        console = page("console")
        self.assertRegex(console, r'id="paletteBtn"[^>]*aria-label="Search commands"')
        self.assertRegex(console, r'id="paletteBtn"[^>]*>\s*<svg class="icon palette-icon"')
        for name in PAGES:
            with self.subTest(page=name):
                self.assertNotIn("\u25d0", page(name), "the theme toggle must be an inline icon, not a font glyph")
                self.assertRegex(page(name), r"data-theme-toggle[^>]*>\s*<svg")

    def test_compact_density_does_not_override_padded_panels(self):
        # Found in the real browser: `:root[data-density=compact] .panel-body` outranked `.pad`, so every padded panel
        # (charts, live run, durations) lost its padding in compact mode.
        css = h.read(os.path.join(h.DASH, "static", "style.css"))
        self.assertIn(':root[data-density="compact"] .panel-body:not(.pad)', css)
        self.assertNotIn(':root[data-density="compact"] .panel-body { padding', css)
        self.assertIn(".panel-body.pad {", css)


class ThemeAssetHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
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

    def get(self, path, auth=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", path, headers={"Authorization": self.auth} if auth else {})
        resp = conn.getresponse()
        body = resp.read()
        headers = {k: v for k, v in resp.getheaders()}
        conn.close()
        return resp.status, headers, body

    def test_theme_js_is_served_as_javascript_with_the_hardening_headers(self):
        status, hdrs, body = self.get("/theme.js")
        self.assertEqual(status, 200)
        self.assertTrue(hdrs["Content-Type"].startswith("application/javascript"))
        self.assertEqual(hdrs["Cache-Control"], "no-cache")
        self.assertIn("default-src 'self'", hdrs["Content-Security-Policy"])  # covers /theme.js; no inline allowed
        self.assertIn(b"ConsoleTheme", body)

    def test_theme_js_requires_authentication(self):
        self.assertEqual(self.get("/theme.js", auth=False)[0], 401)


if __name__ == "__main__":
    unittest.main()
