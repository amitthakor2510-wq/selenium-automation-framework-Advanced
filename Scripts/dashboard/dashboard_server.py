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

In its default configuration this process never runs Maven, git, or any other subprocess, and never
executes anything the browser sends it (the opt-in test runner described below is the one exception) — it only reads target/ and
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

TEST WIZARD (added later, opt-in): /wizard builds test specs, saves templates, and — only when
DASHBOARD_RUNNER_ENABLED=1 — starts runs through runner/allowlist.py, which can execute nothing but
a few fixed commands with validated arguments (no shell, no free-form input). The "never runs a
subprocess" guarantee above therefore holds in the default configuration and is a deliberate,
documented switch otherwise. See docs/dashboard.md.

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
import snapshot as snap  # noqa: E402
import suites as su  # noqa: E402
from auth import users as auth_users  # noqa: E402
from wizard.api import WizardApi  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SUITES_DIR = os.path.join(REPO_ROOT, "testng-suites")
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
        if attempts:
            _failed_auth[client_ip] = attempts
        else:
            # Drop idle clients entirely: keeping an empty list per IP ever seen let this dict grow
            # without bound (one entry per distinct address that ever sent a request).
            _failed_auth.pop(client_ip, None)
        return len(attempts) >= _MAX_ATTEMPTS


def _record_failed_auth(client_ip):
    with _failed_auth_lock:
        _failed_auth.setdefault(client_ip, []).append(time.time())


_SECURITY_HEADERS = (
    ("X-Content-Type-Options", "nosniff"),
    ("X-Frame-Options", "DENY"),
    ("Referrer-Policy", "no-referrer"),
    ("Content-Security-Policy",
     "default-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"),
)

# Matches the guidance in .env.example ("use 12+ characters"); shorter passwords only trigger a warning.
_MIN_PASSWORD_LENGTH = 12

# Largest request body any endpoint accepts (a snapshot import is the biggest).
_MAX_BODY_BYTES = 1_000_000


class DashboardServer(ThreadingHTTPServer):
    """ThreadingHTTPServer with a realistic listen backlog.

    socketserver's default request_queue_size is 5. A page load fires several parallel API calls and a
    second browser tab (or a polling script) doubles that, so connections beyond the fifth were reset
    ("Connection reset by peer") instead of queued. 128 matches the usual OS cap.
    """
    request_queue_size = 128
    daemon_threads = True


def make_handler(password, wizard=None):
    expected_header = "Basic " + base64.b64encode(f"dashboard:{password}".encode("utf-8")).decode("ascii")

    class Handler(BaseHTTPRequestHandler):
        server_version = "FrameworkDashboard/1.0"
        # Per-connection socket timeout: without it a client that opens a connection and then sends
        # nothing (or one byte at a time) pins a server thread forever.
        timeout = 30

        def version_string(self):
            # BaseHTTPRequestHandler appends "Python/x.y.z" to the Server header; send only our own
            # token so the response does not advertise the interpreter version (and no trailing space).
            return self.server_version

        def _security_headers(self):
            """Sent with EVERY response: no framing (clickjacking on the toggle buttons), no MIME
            sniffing, and a same-origin-only CSP (the page ships no inline script or style)."""
            for name, value in _SECURITY_HEADERS:
                self.send_header(name, value)

        def log_message(self, fmt, *args):
            print(f"[dashboard] {self.client_address[0]} - {fmt % args}")

        # ── auth ────────────────────────────────────────────────
        def _authorized(self):
            if _rate_limited(self.client_address[0]):
                return False
            given = self.headers.get("Authorization", "")
            self.user = None
            # hmac.compare_digest: constant-time, avoids leaking the
            # password's length/prefix through response-timing.
            if hmac.compare_digest(given.encode("utf-8"), expected_header.encode("utf-8")):
                # the original shared login keeps working and is an admin
                self.user = {"name": auth_users.LEGACY_USER, "role": "admin"}
                return True
            if given.startswith("Basic ") and auth_users.per_user_mode():
                try:
                    name, _, pw = base64.b64decode(given[6:], validate=True).decode("utf-8").partition(":")
                except (ValueError, UnicodeDecodeError):
                    name, pw = "", ""
                role = auth_users.verify(name, pw) if name and name != auth_users.LEGACY_USER else None
                if role:
                    self.user = {"name": name, "role": role}
                    return True
            # Only a request that actually SENT credentials counts as a failed
            # attempt. Every browser's first request, and the Docker
            # healthcheck, arrive with no Authorization header at all (that is
            # how Basic auth's challenge round works) — counting those would
            # burn the 10-per-minute budget on ordinary page loads and lock
            # out the real user (all ngrok/ssh-tunnel traffic shares one IP).
            if given:
                _record_failed_auth(self.client_address[0])
            return False

        def _require_auth(self):
            if self._authorized():
                return True
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Automation Framework Dashboard"')
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self._security_headers()
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
            self._security_headers()
            self.end_headers()
            self.wfile.write(body)

        def _read_json_body(self):
            """Returns the parsed JSON object, {} for a request with no body, or None after having
            already sent a 400/413 for a body that is oversized, non-numeric-length, not valid JSON
            or not a JSON object. Callers must stop on None: treating a rejected body as "{}" let
            handlers such as /api/snapshot/import and /api/test-config/run-only act on an empty
            request and silently overwrite config (e.g. clearing run.only) while answering 200."""
            try:
                length = int(self.headers.get("Content-Length", 0) or 0)
            except ValueError:
                self._send_json(400, {"error": "invalid Content-Length header"})
                return None
            if length <= 0:
                return {}
            if length > _MAX_BODY_BYTES:
                # Drain (a bounded amount of) the unread body first: answering and closing while the
                # client is still sending makes the OS reset the connection, and the client then sees
                # a broken pipe instead of this 413.
                remaining = min(length, 8 * _MAX_BODY_BYTES)
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                self._send_json(413, {"error": f"request body too large (limit {_MAX_BODY_BYTES} bytes)"})
                return None
            raw = self.rfile.read(length)
            try:
                parsed = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError):
                self._send_json(400, {"error": "request body is not valid JSON"})
                return None
            # Handlers call body.get(...) — a JSON array/string/number would
            # raise AttributeError and surface as a confusing 500.
            if not isinstance(parsed, dict):
                self._send_json(400, {"error": "request body must be a JSON object"})
                return None
            return parsed

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
            self._security_headers()
            self.end_headers()
            self.wfile.write(body)

        def _send_file(self, full, mime, disposition="inline"):
            with open(full, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Disposition", f'{disposition}; filename="{os.path.basename(full)}"')
            self.send_header("Cache-Control", "no-store")
            self._security_headers()
            self.end_headers()
            self.wfile.write(body)

        def _wizard(self, method, parsed, body):
            """Forward /wizard, /wizard/static/*, /control, /control/static/*, /api/features*, /api/wizard/*, /api/templates*, /api/runs*, /api/ci/*, /api/secrets*."""
            if wizard is None:
                return False
            p = parsed.path
            if not (p.startswith(("/wizard", "/control", "/hub", "/api/hub", "/api/wizard/", "/api/features", "/api/templates", "/api/runs", "/api/ci/", "/api/secrets"))):
                return False
            result = wizard.handle(method, p, parse_qs(parsed.query), body, self.user)
            if result[0] == "page":
                ext = os.path.splitext(result[1])[1]
                self._send_file(result[1], _STATIC_MIME.get(ext, "application/octet-stream"))
            elif result[0] == "file":
                self._send_file(result[1], result[2], result[3])
            else:
                self._send_json(result[0], result[1])
            return True

        # ── routing ─────────────────────────────────────────────
        def _method_not_allowed(self):
            """PUT/DELETE/PATCH/OPTIONS: answer 405 with an Allow header (after auth) instead of the stdlib's
            bare 501, and never take any action."""
            if not self._require_auth():
                return
            body = json.dumps({"error": "method not allowed"}).encode("utf-8")
            self.send_response(405)
            self.send_header("Allow", "GET, HEAD, POST")
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self._security_headers()
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        do_PUT = do_DELETE = do_PATCH = do_OPTIONS = _method_not_allowed

        def do_HEAD(self):
            """Liveness probes (load balancers, uptime checkers) commonly use HEAD. Only /api/health answers
            it; every other path is 405, because producing the right headers for a page without running its
            handler would be a guess."""
            if urlparse(self.path).path != "/api/health":
                self._method_not_allowed()
                return
            if not self._require_auth():
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self._security_headers()
            self.end_headers()

        def do_GET(self):
            if not self._require_auth():
                return
            parsed = urlparse(self.path)
            path = parsed.path
            try:
                if self._wizard("GET", parsed, {}):
                    return
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
                elif path == "/api/suites":
                    known = [s["name"] for s in cf.read_sites(PIPELINE_CONFIG)]
                    self._send_json(200, su.list_suites(SUITES_DIR, known))
                elif path == "/api/snapshot":
                    snapshot = snap.build(cf.read_sites(PIPELINE_CONFIG), cf.read_test_config(TEST_CONFIG))
                    body = json.dumps(snapshot, indent=2).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Content-Disposition", 'attachment; filename="dashboard-snapshot.json"')
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Cache-Control", "no-store")
                    self._security_headers()
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self._serve_static(path)
            except FileNotFoundError as e:
                print(f"[dashboard] 404 on GET {path}: {e}")
                self._send_json(404, {"error": "file not found"})
            except Exception as e:  # noqa: BLE001 — always answer the browser, never hang it
                self._internal_error("GET", path, e)

        def _internal_error(self, method, path, exc):
            """Log the real exception server-side; the browser only gets a generic message, because
            exception text routinely contains absolute file-system paths and library internals."""
            print(f"[dashboard] 500 on {method} {path}: {type(exc).__name__}: {exc}")
            self._send_json(500, {"error": "internal server error (details are in the dashboard log)"})

        def _audit(self, action, target, field, old_value, new_value):
            try:
                audit.record(REPO_ROOT, actor=f"{self.user['name']}@{self.client_address[0]}", action=action,
                             target=target, field=field, old_value=old_value, new_value=new_value)
            except OSError as e:
                # The config write already succeeded by the time this runs —
                # a logging failure (disk full, permissions) must not be
                # reported back as if the actual change failed.
                print(f"[dashboard] audit log write failed (change itself still applied): {e}")

        def _csrf_ok(self):
            """
            Browsers attach cached Basic-auth credentials to ANY request to
            this origin, including a cross-site form/fetch fired from an
            unrelated web page the user happens to have open. Two cheap,
            standard defences, both required:
              * Content-Type must be application/json. A cross-site page
                cannot send that without a CORS preflight (which this server
                never answers), so a plain <form> or text/plain fetch is
                rejected here.
              * If the browser sent an Origin header, its host must equal the
                Host header (same-origin). Requests without Origin (curl,
                scripts) are not browser cross-site requests and pass.
            """
            content_type = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            if content_type != "application/json":
                return False
            origin = self.headers.get("Origin")
            if origin:
                origin_host = urlparse(origin).netloc
                if not origin_host or origin_host != (self.headers.get("Host") or ""):
                    return False
            return True

        def do_POST(self):
            if not self._require_auth():
                return
            if not self._csrf_ok():
                self._send_json(403, {"error": "POST requests must be same-origin with Content-Type: application/json"})
                return
            parsed = urlparse(self.path)
            path = parsed.path
            body = self._read_json_body()
            if body is None:
                return
            try:
                if self._wizard("POST", parsed, body):
                    return
                if auth_users.per_user_mode() and not auth_users.allowed(self.user["role"], "config.edit"):
                    self._send_json(403, {"error": f"your role ({self.user['role']}) can't change the test configuration"})
                    return
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

                elif path == "/api/snapshot/import":
                    clean, problems = snap.validate(
                        body,
                        known_sites={x["name"] for x in cf.read_sites(PIPELINE_CONFIG)},
                        known_tests={t["name"] for sec in cf.read_test_config(TEST_CONFIG)["sections"]
                                     for t in sec["tests"]})
                    if clean is None:
                        self._send_json(400, {"error": "; ".join(problems) or "invalid snapshot"})
                        return
                    snap.apply(PIPELINE_CONFIG, TEST_CONFIG, clean)
                    self._audit("snapshot", "import",
                                f"{len(clean['sites'])} site(s), {len(clean['tests'])} test(s), "
                                f"{len(clean['groups'])} group(s)", None,
                                f"run_only={clean.get('run_only', '')}")
                    self._send_json(200, {
                        "applied": {"sites": len(clean["sites"]), "tests": len(clean["tests"]),
                                    "groups": len(clean["groups"])},
                        "problems": problems,
                        "sites": cf.read_sites(PIPELINE_CONFIG),
                        "test_config": cf.read_test_config(TEST_CONFIG),
                    })

                elif path == "/api/audit/undo":
                    entry = audit.peek_last(REPO_ROOT)
                    if entry is None:
                        self._send_json(404, {"error": "nothing to undo"})
                        return
                    # Revert FIRST, drop the log line only once that worked —
                    # a revert that raises (e.g. a preset/bulk entry that has
                    # no single old value) must leave the entry in the log.
                    self._revert(entry)
                    audit.discard_last(REPO_ROOT)
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
                self._internal_error("POST", path, e)

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
    # Loopback by default: this server can rewrite the test/pipeline config, so exposing it on every
    # interface is an explicit choice (--host 0.0.0.0 / DASHBOARD_HOST=0.0.0.0), never the default.
    parser.add_argument("--host", default=os.environ.get("DASHBOARD_HOST", "127.0.0.1"))
    args = parser.parse_args()

    password, generated = _resolve_password()
    wizard = WizardApi(REPO_ROOT, PIPELINE_CONFIG, SUITES_DIR)
    handler = make_handler(password, wizard)
    httpd = DashboardServer((args.host, args.port), handler)

    from runner import runner_service
    banner_lines = [
        "Selenium Automation Framework — Dashboard",
        f"  Repo root : {REPO_ROOT}",
        f"  Listening : http://{args.host}:{args.port}  (open http://localhost:{args.port} locally)",
        "  Username  : dashboard",
        f"  Test Hub  : http://localhost:{args.port}/hub  (START HERE: pick what to test, answer a few questions, read the result)",
        f"  Control Center: http://localhost:{args.port}/control  (every framework feature as a switch/button)",
        f"  Test wizard: http://localhost:{args.port}/wizard  (running tests from it: "
        + ("ON" if runner_service.enabled() else "off — set DASHBOARD_RUNNER_ENABLED=1") + ")",
    ]
    if auth_users.per_user_mode():
        banner_lines.append(f"  Accounts  : per-user logins from {auth_users.users_path()} (plus the shared 'dashboard' admin)")
    if generated:
        banner_lines += [
            f"  Password  : {password}   <-- generated (set DASHBOARD_PASSWORD to fix this)",
            "  This password changes every restart. Set DASHBOARD_PASSWORD in your",
            "  environment/.env if you want a stable one, especially before tunnelling",
            "  this with ngrok or similar.",
        ]
    else:
        banner_lines.append("  Password  : (from DASHBOARD_PASSWORD)")
        if len(password) < _MIN_PASSWORD_LENGTH:
            banner_lines += [
                f"  WARNING   : DASHBOARD_PASSWORD is shorter than {_MIN_PASSWORD_LENGTH} characters. This port",
                "              can edit your test config - use a longer password, above all",
                "              before binding it beyond localhost or tunnelling it.",
            ]
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
