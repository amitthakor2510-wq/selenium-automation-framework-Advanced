"""
Starts runs from validated specs and tracks their status.

Flow:  validate -> create workspace -> queue -> (worker) convert spec to files + properties ->
resolve secret references into the child's environment -> run ONE allowlisted command ->
snapshot reports -> write status.json / run.log / report-link.txt.

Disabled unless DASHBOARD_RUNNER_ENABLED=1: the original console never executed anything
(see dashboard_server.py's docstring), so running tests from a web request is an explicit
opt-in per deployment, not a side effect of upgrading.
"""
import html
import os
import shutil
import signal
import subprocess
import threading
import time
from datetime import datetime, timezone

from auth import secrets_store
from reports import evidence_options, report_options
from runner import allowlist, ci_trigger, workspace
from runner.queue import RunQueue
from specs.converters import convert
from wizard import validators

KILL_GRACE_SECONDS = 10
TIMEOUT_UNIT_SECONDS = 60  # a spec's timeoutMinutes; tests shrink this


def enabled():
    return os.environ.get("DASHBOARD_RUNNER_ENABLED", "").strip() == "1"


def _now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class RunnerDisabled(Exception):
    pass


class RunnerService:
    def __init__(self, repo_root, ctx_provider, max_parallel=None):
        self.repo_root = repo_root
        self._ctx = ctx_provider
        self.queue = RunQueue(self._execute, max_parallel)
        self._procs, self._cancelled = {}, set()
        self._ci = {}
        self._lock = threading.Lock()
        try:
            workspace.cleanup()
        except OSError:
            pass

    # ── public API ──────────────────────────────────────────────
    def start(self, spec, user):
        if not enabled():
            raise RunnerDisabled("running tests from the dashboard is switched off (set DASHBOARD_RUNNER_ENABLED=1)")
        result = validators.validate_spec(spec, self._ctx())
        if not result["ok"]:
            raise ValueError("; ".join(f"{e['path']}: {e['message']}" for e in result["errors"][:5]))
        run_id = workspace.new_run_id()
        workspace.create(run_id, spec)
        workspace.write_status(run_id, id=run_id, name=spec["name"], type=spec["type"], state="queued",
                               startedBy=user, createdAt=_now(), message="")
        try:
            self.queue.submit(run_id)
        except Exception:
            shutil.rmtree(workspace.path_for(run_id), ignore_errors=True)
            raise
        return run_id

    def status(self, run_id):
        st = workspace.read_status(run_id)
        st["queuePosition"] = self.queue.position(run_id)
        return st

    def cancel(self, run_id):
        """True if something was cancelled."""
        if self.queue.cancel_pending(run_id):
            workspace.write_status(run_id, state="cancelled", finishedAt=_now(), message="cancelled before it started")
            return True
        with self._lock:
            proc = self._procs.get(run_id)
            if proc is None:
                return False
            self._cancelled.add(run_id)
        self._terminate(proc)
        return True

    def log_tail(self, run_id, max_bytes=200_000, offset=0):
        p = os.path.join(workspace.path_for(run_id), "run.log")
        if not os.path.isfile(p):
            return {"text": "", "offset": 0}
        size = os.path.getsize(p)
        start = offset if 0 <= offset <= size else max(0, size - max_bytes)
        if size - start > max_bytes:
            start = size - max_bytes
        with open(p, "rb") as f:
            f.seek(start)
            data = f.read(max_bytes)
        return {"text": data.decode("utf-8", "replace"), "offset": start + len(data)}

    # ── CI hand-off ──────────────────────────────────────────────
    def ci_start(self, provider, params, ref=None):
        handle = ci_trigger.start(provider, params, ref)
        token = f"{provider}-{int(time.time() * 1000)}"
        with self._lock:
            self._ci[token] = handle
            if len(self._ci) > 50:
                self._ci.pop(next(iter(self._ci)))
        return token

    def ci_status(self, token):
        with self._lock:
            handle = self._ci.get(token)
        if handle is None:
            raise KeyError(token)
        return ci_trigger.poll(handle)

    # ── execution ────────────────────────────────────────────────
    def _terminate(self, proc):
        try:
            if os.name == "nt":
                proc.terminate()
            else:
                os.killpg(proc.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            return

        def reaper():
            try:
                proc.wait(timeout=KILL_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL) if os.name != "nt" else proc.kill()
                except (ProcessLookupError, PermissionError):
                    pass
        threading.Thread(target=reaper, daemon=True).start()

    def _execute(self, run_id):
        d = workspace.path_for(run_id)
        started = time.time()
        try:
            import json
            with open(os.path.join(d, "spec.json"), encoding="utf-8") as f:
                spec = json.load(f)
            plan = convert(spec, os.path.join(d, "inputs"))
            props = dict(plan.get("properties", {}))
            props.update(report_options.to_properties(spec["type"], spec.get("reports")))
            props.update(evidence_options.to_properties(spec["type"], spec.get("evidence")))
            plan["properties"] = props
            resolved = {}
            for var, name in plan.get("secret_env", {}).items():
                try:
                    resolved[var] = secrets_store.get_secret(name)
                except (KeyError, secrets_store.SecretsUnavailable):
                    raise RuntimeError(f"secret '{name}' is not available") from None
            argv = allowlist.build_command(spec["type"], plan, self.repo_root, d)
            env = allowlist.build_env(plan, resolved)
            workspace.write_properties_generated(run_id, props)
            workspace.write_status(run_id, state="running", startedAt=_now(), command=" ".join(argv))
            timeout = int(spec.get("run", {}).get("timeoutMinutes", 30)) * TIMEOUT_UNIT_SECONDS
            code, outcome = self._run(run_id, argv, env, d, timeout)
            self._redact_log(d, resolved.values())
            state = {"ok": "passed" if code == 0 else "failed"}.get(outcome, outcome)
            copied = workspace.snapshot_reports(run_id, self.repo_root, report_options.artifact_dirs(spec["type"], spec.get("reports")))
            link = self._write_summary(run_id, spec, state, code, time.time() - started, copied)
            workspace.write_status(run_id, state=state, exitCode=code, finishedAt=_now(), reportLink=link, reports=copied,
                                   message="" if state == "passed" else f"finished with exit code {code}" if outcome == "ok" else outcome)
        except Exception as e:  # noqa: BLE001 — every failure must land in status.json
            workspace.write_status(run_id, state="error", finishedAt=_now(), message=str(e)[:300])
        finally:
            try:
                workspace.cleanup(self.queue.active_ids() | {run_id})
            except OSError:
                pass

    def _run(self, run_id, argv, env, d, timeout):
        with open(os.path.join(d, "run.log"), "ab") as log:
            log.write(f"$ {' '.join(argv)}\n".encode())
            log.flush()
            proc = subprocess.Popen(argv, cwd=self.repo_root, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                    stderr=subprocess.STDOUT, shell=False, start_new_session=(os.name != "nt"))
            with self._lock:
                self._procs[run_id] = proc
            deadline = time.time() + timeout
            timed_out = False
            try:
                while True:
                    try:
                        proc.wait(timeout=0.5)
                        break
                    except subprocess.TimeoutExpired:
                        if time.time() > deadline and not timed_out:
                            timed_out = True
                            self._terminate(proc)
            finally:
                with self._lock:
                    self._procs.pop(run_id, None)
                    cancelled = run_id in self._cancelled
                    self._cancelled.discard(run_id)
        if cancelled:
            return proc.returncode, "cancelled"
        if timed_out:
            return proc.returncode, "timeout"
        return proc.returncode, "ok"

    @staticmethod
    def _redact_log(d, values):
        p = os.path.join(d, "run.log")
        values = [v for v in values if v]
        if not values or not os.path.isfile(p):
            return
        with open(p, encoding="utf-8", errors="replace") as f:
            text = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(allowlist.redact(text, values))

    def _write_summary(self, run_id, spec, state, code, seconds, copied):
        """Run summary page (CSP-safe static HTML) + a zip of each report folder."""
        d = workspace.path_for(run_id)
        links = []
        for name in copied:
            src = os.path.join(d, "report", name)
            shutil.make_archive(os.path.join(d, "report", name), "zip", src)
            links.append(f'<li><a href="/api/runs/{run_id}/files/report/{html.escape(name)}.zip">{html.escape(name)}.zip</a></li>')
        rows = "".join(f"<tr><th>{html.escape(k)}</th><td>{html.escape(str(v))}</td></tr>" for k, v in (
            ("Run", run_id), ("Name", spec["name"]), ("Type", spec["type"]), ("Result", state),
            ("Exit code", code), ("Duration", f"{seconds:.0f} s")))
        page = ("<!doctype html><meta charset=utf-8><title>Run summary</title>"
                '<link rel=stylesheet href="/wizard/static/wizard.css"><body class=summary-page><h1>Run summary</h1>'
                f"<table>{rows}</table><h2>Reports</h2><ul>{''.join(links) or '<li>none captured</li>'}</ul>"
                f'<p><a href="/api/runs/{run_id}/files/run.log">run.log</a></p>')
        with open(os.path.join(d, "summary.html"), "w", encoding="utf-8") as f:
            f.write(page)
        link = f"/api/runs/{run_id}/files/summary.html"
        with open(os.path.join(d, "report-link.txt"), "w", encoding="utf-8") as f:
            f.write(link + "\n")
        return link
