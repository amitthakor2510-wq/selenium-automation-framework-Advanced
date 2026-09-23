#!/usr/bin/env python3
"""
Automation Framework Dashboard — a small local web UI over two things:

  1. VIEW recent test results (target/surefire-reports, coverage,
     self-healing, flaky-test, TIA data) from the last local `mvn test` run.
  2. TOGGLE which sites and tests run, by editing pipeline-config.properties
     and test-config.properties directly (see docs/configuration.md and
     docs/dashboard.md) — the same two files `mvn test` itself reads, so a
     change here takes effect on the very next run, no restart needed.

Deliberately stdlib-only (http.server + json + re), matching this repo's
own CI-script convention (see notify_chat.py's module docstring) — no
pip install, no extra dependency to keep in sync with pom.xml. Runs
directly:

    python3 Scripts/dashboard/dashboard_server.py

or via the dashboard Docker service (docker compose --profile dashboard up,
see docker-compose.dashboard.yml) — same script either way, just started
from a container instead of the host.

This process NEVER runs Maven, git, or any other subprocess, and never
executes anything the browser sends it — it only reads target/ and
reads/edits the two properties files above via config_files.py's
narrow, validated setters (site/test/group names are regex-checked
before touching a file; free text only ever lands in run.only, and even
that is character-restricted — see config_files.py). There is nothing
here for a "run tests" button to call; that was a deliberate scope cut
(see docs/dashboard.md) precisely because executing pipeline commands
from a web request is a very different security posture than editing
two config files.

Also: named site presets (Scripts/dashboard/presets.json), bulk
enable/disable of a whole test section in one write, a Groups panel,
a read-only raw-file viewer, and an audit log of every dashboard-made
change with an "undo last change" action. See docs/dashboard.md.

Auth: HTTP Basic, username "dashboard", password from the
DASHBOARD_PASSWORD environment variable. If that variable is unset, the
server generates a random one-time password and prints it to stdout at
startup (loud, boxed, impossible to miss in `docker compose logs`) —
the dashboard NEVER starts wide open, because docs/dashboard.md
explicitly recommends tunnelling this (ngrok, ssh -L) to share it, and
an unauthenticated port editing your test config is not something to
expose even for a minute.
"""
import argparse
import base64
import hmac
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit  # noqa: E402
import config_files as cf  # noqa: E402
import presets as ps  # noqa: E402
import results as res  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
PIPELINE_CONFIG = os.path.join(REPO_ROOT, "pipeline-config.properties")
TEST_CONFIG = os.path.join(REPO_ROOT, "test-config.properties")
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

_STATIC_MIME = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8",
                ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml"}

# Simple in-process rate limit on failed auth attempts, keyed by client IP —
# this is a LAN/tunnel convenience tool, not a hardened login page, but a
# bare Basic-Auth prompt with no throttling at all is an easy thing to get
# wrong, especially once someone shares an ngrok URL.
_failed_auth = {}
_failed_auth_lock = threading.Lock()
_MAX_ATTEMPTS = 10
_WINDOW_SECONDS = 60


def _rate_limited(client_ip):
    now = time.time()
    with _failed_auth_lock:
        attempts = [t for t in _failed_auth.get(client_ip, []) if now - t < _WINDOW_SECONDS]
        _failed_auth[client_ip] = attempts
        return len(attempts) >= _MAX_ATTEMPTS


def _record_failed_auth(client_ip):
    with _failed_auth_lock:
        _failed_auth.setdefault(client_ip, []).append(time.time())


def make_handler(password):
    expected_header = "Basic " + base64.b64encode(f"dashboard:{password}".encode("utf-8")).decode("ascii")

    class Handler(BaseHTTPRequestHandler):
        server_version = "FrameworkDashboard/1.0"

        def log_message(self, fmt, *args):
            print(f"[dashboard] {self.client_address[0]} - {fmt % args}")

        # ── auth ────────────────────────────────────────────────
        def _authorized(self):
            if _rate_limited(self.client_address[0]):
                return False
            given = self.headers.get("Authorization", "")
            # hmac.compare_digest: constant-time, avoids leaking the
            # password's length/prefix through response-timing.
            ok = hmac.compare_digest(given, expected_header)
            if not ok:
                _record_failed_auth(self.client_address[0])
            return ok

        def _require_auth(self):
            if self._authorized():
                return True
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Automation Framework Dashboard"')
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Authentication required.")
            return False

        # ── plumbing ────────────────────────────────────────────
        def _send_json(self, status, payload):
            body = json.dumps(payload, indent=2).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _read_json_body(self):
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length <= 0 or length > 1_000_000:
                return {}
            raw = self.rfile.read(length)
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                return {}

        def _serve_static(self, path):
            if path == "/":
                path = "/index.html"
            safe_rel = os.path.normpath(path).lstrip(os.sep).lstrip("/")
            full = os.path.join(STATIC_DIR, safe_rel)
            # normpath collapses "..", but belt-and-suspenders: refuse
            # anything that resolves outside STATIC_DIR.
            if not os.path.abspath(full).startswith(os.path.abspath(STATIC_DIR) + os.sep) and full != os.path.abspath(STATIC_DIR):
                self.send_error(404)
                return
            if not os.path.isfile(full):
                self.send_error(404)
                return
            ext = os.path.splitext(full)[1]
            with open(full, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", _STATIC_MIME.get(ext, "application/octet-stream"))
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        # ── routing ─────────────────────────────────────────────
        def do_GET(self):
            if not self._require_auth():
                return
            parsed = urlparse(self.path)
            path = parsed.path
            try:
                if path == "/api/health":
                    self._send_json(200, {"status": "ok", "repo_root": REPO_ROOT})
                elif path == "/api/pipeline-config":
                    self._send_json(200, {"sites": cf.read_sites(PIPELINE_CONFIG)})
                elif path == "/api/pipeline-config/raw":
                    self._send_json(200, {"path": PIPELINE_CONFIG, "text": cf.read_raw(PIPELINE_CONFIG)})
                elif path == "/api/pipeline-config/presets":
                    self._send_json(200, {"presets": ps.load()})
                elif path == "/api/test-config":
                    self._send_json(200, cf.read_test_config(TEST_CONFIG))
                elif path == "/api/test-config/raw":
                    self._send_json(200, {"path": TEST_CONFIG, "text": cf.read_raw(TEST_CONFIG)})
                elif path == "/api/results":
                    self._send_json(200, res.full_snapshot(REPO_ROOT))
                elif path == "/api/audit":
                    self._send_json(200, {"entries": audit.recent(REPO_ROOT)})
                else:
                    self._serve_static(path)
            except FileNotFoundError as e:
                self._send_json(404, {"error": str(e)})
            except Exception as e:  # noqa: BLE001 — always answer the browser, never hang it
                self._send_json(500, {"error": str(e)})

        def _audit(self, action, target, field, old_value, new_value):
            try:
                audit.record(REPO_ROOT, actor=self.client_address[0], action=action,
                             target=target, field=field, old_value=old_value, new_value=new_value)
            except OSError as e:
                # The config write already succeeded by the time this runs —
                # a logging failure (disk full, permissions) must not be
                # reported back as if the actual change failed.
                print(f"[dashboard] audit log write failed (change itself still applied): {e}")

        def do_POST(self):
            if not self._require_auth():
                return
            path = urlparse(self.path).path
            body = self._read_json_body()
            try:
                if path == "/api/pipeline-config/site":
                    name, enabled = body.get("name"), bool(body.get("enabled"))
                    before = cf.PropertiesFile(PIPELINE_CONFIG).get(f"site.{name}.enabled")
                    cf.set_site_enabled(PIPELINE_CONFIG, name, enabled)
                    self._audit("site", name, "enabled", before, str(enabled).lower())
                    self._send_json(200, {"sites": cf.read_sites(PIPELINE_CONFIG)})

                elif path == "/api/pipeline-config/preset":
                    preset_id = body.get("id")
                    preset = ps.find(preset_id)
                    if preset is None:
                        self._send_json(404, {"error": f"no such preset: {preset_id!r}"})
                        return
                    known = [s["name"] for s in cf.read_sites(PIPELINE_CONFIG)]
                    skipped = ps.apply(PIPELINE_CONFIG, preset, known)
                    self._audit("preset", preset_id, "sites",
                                None, json.dumps(preset["sites"]))
                    self._send_json(200, {"sites": cf.read_sites(PIPELINE_CONFIG), "skipped": skipped})

                elif path == "/api/test-config/test":
                    name, enabled = body.get("name"), bool(body.get("enabled"))
                    before = cf.PropertiesFile(TEST_CONFIG).get(f"test.{name}.enabled")
                    cf.set_test_enabled(TEST_CONFIG, name, enabled)
                    self._audit("test", name, "enabled", before, str(enabled).lower())
                    self._send_json(200, cf.read_test_config(TEST_CONFIG))

                elif path == "/api/test-config/bulk-section":
                    names = body.get("names") or []
                    enabled = bool(body.get("enabled"))
                    if not isinstance(names, list) or not names:
                        self._send_json(400, {"error": "'names' must be a non-empty list"})
                        return
                    cf.set_tests_enabled(TEST_CONFIG, names, enabled)
                    self._audit("test-bulk", ", ".join(names), "enabled",
                                None, str(enabled).lower())
                    self._send_json(200, cf.read_test_config(TEST_CONFIG))

                elif path == "/api/test-config/group":
                    name, enabled = body.get("name"), bool(body.get("enabled"))
                    before = cf.PropertiesFile(TEST_CONFIG).get(f"group.{name}.enabled")
                    cf.set_group_enabled(TEST_CONFIG, name, enabled)
                    self._audit("group", name, "enabled", before, str(enabled).lower())
                    self._send_json(200, cf.read_test_config(TEST_CONFIG))

                elif path == "/api/test-config/run-only":
                    before = cf.PropertiesFile(TEST_CONFIG).get("run.only")
                    cf.set_run_only(TEST_CONFIG, body.get("value", ""))
                    after = cf.PropertiesFile(TEST_CONFIG).get("run.only")
                    self._audit("run-only", "run.only", "value", before, after)
                    self._send_json(200, cf.read_test_config(TEST_CONFIG))

                elif path == "/api/audit/undo":
                    entry = audit.pop_last(REPO_ROOT)
                    if entry is None:
                        self._send_json(404, {"error": "nothing to undo"})
                        return
                    self._revert(entry)
                    self._send_json(200, {
                        "reverted": entry,
                        "sites": cf.read_sites(PIPELINE_CONFIG),
                        "test_config": cf.read_test_config(TEST_CONFIG),
                    })

                else:
                    self._send_json(404, {"error": "no such endpoint"})
            except ValueError as e:
                self._send_json(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001
                self._send_json(500, {"error": str(e)})

        def _revert(self, entry):
            """
            Best-effort undo of one audit-log entry, back to its recorded
            old_value. "preset" and "test-bulk" entries changed several
            keys as one write but the log only kept the combined
            before/after (see do_POST) — those two kinds are intentionally
            NOT revertible (there is no single old value to restore to),
            so Undo is only offered for the four single-key actions.
            """
            action, target, old_value = entry["action"], entry["target"], entry["old_value"]
            if old_value is None:
                raise ValueError(f"cannot undo a '{action}' change (no single previous value recorded)")
            if action == "site":
                cf.set_site_enabled(PIPELINE_CONFIG, target, old_value.strip().lower() == "true")
            elif action == "test":
                cf.set_test_enabled(TEST_CONFIG, target, old_value.strip().lower() == "true")
            elif action == "group":
                cf.set_group_enabled(TEST_CONFIG, target, old_value.strip().lower() == "true")
            elif action == "run-only":
                cf.set_run_only(TEST_CONFIG, old_value)
            else:
                raise ValueError(f"undo not supported for '{action}' changes")

    return Handler


def _resolve_password():
    env_password = os.environ.get("DASHBOARD_PASSWORD", "").strip()
    if env_password:
        return env_password, False
    return secrets.token_urlsafe(18), True


def main():
    parser = argparse.ArgumentParser(description="Selenium Automation Framework — local dashboard")
    parser.add_argument("--port", type=int, default=int(os.environ.get("DASHBOARD_PORT", "5057")))
    parser.add_argument("--host", default=os.environ.get("DASHBOARD_HOST", "0.0.0.0"))
    args = parser.parse_args()

    password, generated = _resolve_password()
    handler = make_handler(password)
    httpd = ThreadingHTTPServer((args.host, args.port), handler)

    banner_lines = [
        "Selenium Automation Framework — Dashboard",
        f"  Repo root : {REPO_ROOT}",
        f"  Listening : http://{args.host}:{args.port}  (open http://localhost:{args.port} locally)",
        "  Username  : dashboard",
    ]
    if generated:
        banner_lines += [
            f"  Password  : {password}   <-- generated (set DASHBOARD_PASSWORD to fix this)",
            "  This password changes every restart. Set DASHBOARD_PASSWORD in your",
            "  environment/.env if you want a stable one, especially before tunnelling",
            "  this with ngrok or similar.",
        ]
    else:
        banner_lines.append("  Password  : (from DASHBOARD_PASSWORD)")
    width = max(len(line) for line in banner_lines) + 4
    print("=" * width)
    for line in banner_lines:
        print(f"  {line}")
    print("=" * width)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[dashboard] shutting down.")
        httpd.shutdown()


if __name__ == "__main__":
    main()
