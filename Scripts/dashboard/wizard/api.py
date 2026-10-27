"""
HTTP-independent request handling for the test wizard (dashboard_server.py just forwards to it).

handle(method, path, query, body, user) -> (status, payload)
                                          or ("file", fullpath, mime, disposition)
                                          or ("page", fullpath)    # static wizard assets
`user` = {"name": ..., "role": ...}. Every action is permission-checked here and written to the
wizard audit channel (target/dashboard-wizard-audit.jsonl), separate from the config-toggle log so
"Undo last change" there is never blocked by a wizard entry.
"""
import os
import re

import audit
import config_files as cf
import suites as su
from auth import secrets_store, users
from reports import evidence_options, report_options
from runner import ci_trigger, workspace
from runner.queue import QueueFull
from runner.runner_service import RunnerDisabled, RunnerService
from specs import spec_store
from wizard import validators

_HERE = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(_HERE, "static")
_MIME = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
         ".json": "application/json", ".log": "text/plain; charset=utf-8", ".txt": "text/plain; charset=utf-8",
         ".zip": "application/zip", ".generated": "text/plain; charset=utf-8"}
_RUN_FILE_OK = re.compile(r"^(summary\.html|run\.log|spec\.json|properties\.generated|report-link\.txt|report/[A-Za-z0-9_.-]+\.zip)$")
_TYPE_TITLES = ("ui", "api", "performance", "crawler", "accessibility")


class WizardApi:
    def __init__(self, repo_root, pipeline_config, suites_dir):
        self.repo_root, self.pipeline_config, self.suites_dir = repo_root, pipeline_config, suites_dir
        self.runner = RunnerService(repo_root, self.build_ctx)

    # ── context for validators ───────────────────────────────────
    def build_ctx(self):
        sites = cf.read_sites(self.pipeline_config)
        browser_sites = [s["name"] for s in sites if s["type"] == "browser"]
        by_site = su.list_suites(self.suites_dir, [s["name"] for s in sites])["bySite"]
        names = None
        if secrets_store.available():
            try:
                names = secrets_store.list_names()
            except (OSError, ValueError):
                names = None
        return {"sites": browser_sites,
                "sites_for": {"perf": [n for n, fs in by_site.items() if f"{n}-perf.xml" in fs],
                              "accessibility": [n for n, fs in by_site.items() if f"{n}-accessibility.xml" in fs]},
                "secret_names": names}

    def _audit(self, user, action, target, new_value=""):
        try:
            audit.record(self.repo_root, actor=f"{user['name']}", action=action, target=target, field="",
                         old_value=None, new_value=new_value, channel="wizard")
        except OSError:
            pass

    # ── routing ──────────────────────────────────────────────────
    def handle(self, method, path, query, body, user):
        role = user["role"]

        def need(perm):
            if not users.allowed(role, perm):
                raise PermissionError(f"your role ({role}) can't do this ({perm})")

        try:
            if method == "GET":
                return self._get(path, query, user, need)
            return self._post(path, body, user, need)
        except PermissionError as e:
            return 403, {"error": str(e)}
        except RunnerDisabled as e:
            return 403, {"error": str(e)}
        except secrets_store.SecretsUnavailable as e:
            return 409, {"error": str(e)}
        except (QueueFull, ci_trigger.CiError) as e:
            return 409 if isinstance(e, QueueFull) else 502, {"error": str(e)}
        except FileNotFoundError as e:
            return 404, {"error": str(e)}
        except (ValueError, KeyError) as e:
            return 400, {"error": str(e).strip("'\"")}

    def _get(self, path, query, user, need):
        if path in ("/wizard", "/wizard/"):
            return "page", os.path.join(STATIC_DIR, "wizard.html")
        if path.startswith("/wizard/static/"):
            rel = os.path.normpath(path[len("/wizard/static/"):])
            full = os.path.join(STATIC_DIR, rel)
            if rel.startswith("..") or os.path.isabs(rel) or not os.path.isfile(full):
                raise FileNotFoundError("not found")
            return "page", full
        if path == "/api/wizard/options":
            return 200, self._options(user)
        m = re.fullmatch(r"/api/wizard/forms/([a-z]+)", path)
        if m:
            need("spec.view")
            try:
                return 200, validators.load_form(m.group(1))
            except KeyError:
                raise FileNotFoundError("unknown test type") from None
        if path == "/api/templates":
            need("spec.view")
            return 200, {"templates": spec_store.list_templates()}
        m = re.fullmatch(r"/api/templates/([a-z0-9-]+)", path)
        if m:
            need("spec.view")
            v = query.get("version", [None])[0]
            return 200, spec_store.load(m.group(1), int(v) if v else None)
        if path == "/api/runs":
            need("run.view")
            return 200, {"runs": workspace.list_runs(), "enabled": self._enabled()}
        m = re.fullmatch(r"/api/runs/([0-9a-f-]+)", path)
        if m:
            need("run.view")
            return 200, self.runner.status(m.group(1))
        m = re.fullmatch(r"/api/runs/([0-9a-f-]+)/log", path)
        if m:
            need("run.view")
            off = int(query.get("offset", ["-1"])[0])
            return 200, self.runner.log_tail(m.group(1), offset=off)
        m = re.fullmatch(r"/api/runs/([0-9a-f-]+)/files/(.+)", path)
        if m:
            need("run.view")
            rel = m.group(2)
            if not _RUN_FILE_OK.match(rel):
                raise FileNotFoundError("not found")
            full = os.path.join(workspace.path_for(m.group(1)), rel)
            if not os.path.isfile(full):
                raise FileNotFoundError("not found")
            ext = os.path.splitext(full)[1]
            return "file", full, _MIME.get(ext, "application/octet-stream"), "attachment" if ext == ".zip" else "inline"
        m = re.fullmatch(r"/api/ci/([a-z0-9-]+)", path)
        if m:
            need("ci.trigger")
            return 200, self.runner.ci_status(m.group(1))
        if path == "/api/secrets":
            need("secrets.manage")
            return 200, {"available": secrets_store.available(), "names": secrets_store.list_names() if secrets_store.available() else []}
        raise FileNotFoundError("no such endpoint")

    def _post(self, path, body, user, need):
        if path == "/api/wizard/validate":
            need("spec.view")
            return 200, validators.validate_spec(body.get("spec"), self.build_ctx())
        if path == "/api/templates":
            need("spec.save")
            saved = spec_store.save(body.get("spec"), user["name"], self.build_ctx())
            self._audit(user, "template-save", saved["slug"], f"v{saved['version']}")
            return 200, saved
        m = re.fullmatch(r"/api/templates/([a-z0-9-]+)/delete", path)
        if m:
            need("template.delete")
            spec_store.delete(m.group(1))
            self._audit(user, "template-delete", m.group(1))
            return 200, {"deleted": m.group(1)}
        if path == "/api/runs":
            need("run.start")
            spec = body.get("spec")
            if spec is None and body.get("template"):
                spec = spec_store.load(body["template"], body.get("version"))
            run_id = self.runner.start(spec, user["name"])
            self._audit(user, "run-start", run_id, spec.get("name", ""))
            return 200, self.runner.status(run_id)
        m = re.fullmatch(r"/api/runs/([0-9a-f-]+)/cancel", path)
        if m:
            need("run.cancel")
            run_id = m.group(1)
            if self.runner.status(run_id).get("startedBy") != user["name"]:
                need("run.cancel.any")
            ok = self.runner.cancel(run_id)
            self._audit(user, "run-cancel", run_id)
            return 200, {"cancelled": ok}
        m = re.fullmatch(r"/api/runs/([0-9a-f-]+)/rerun", path)
        if m:
            need("run.start")
            import json
            with open(os.path.join(workspace.path_for(m.group(1)), "spec.json"), encoding="utf-8") as f:
                spec = json.load(f)
            run_id = self.runner.start(spec, user["name"])
            self._audit(user, "run-start", run_id, f"rerun of {m.group(1)}")
            return 200, self.runner.status(run_id)
        if path == "/api/ci/trigger":
            need("ci.trigger")
            token = self.runner.ci_start(body.get("provider"), body.get("params"), body.get("ref"))
            self._audit(user, "ci-trigger", str(body.get("provider")), str(body.get("params")))
            return 200, {"token": token}
        if path == "/api/secrets":
            need("secrets.manage")
            secrets_store.set_secret(body.get("name"), body.get("value"))
            self._audit(user, "secret-set", str(body.get("name")))
            return 200, {"names": secrets_store.list_names()}
        if path == "/api/secrets/delete":
            need("secrets.manage")
            secrets_store.delete_secret(body.get("name"))
            self._audit(user, "secret-delete", str(body.get("name")))
            return 200, {"names": secrets_store.list_names()}
        raise FileNotFoundError("no such endpoint")

    @staticmethod
    def _enabled():
        from runner import runner_service
        return runner_service.enabled()

    def _options(self, user):
        ctx = self.build_ctx()
        types = []
        for t in _TYPE_TITLES:
            f = validators.load_form(t)
            types.append({"type": t, "title": f["title"], "description": f["description"],
                          "reports": report_options.available(t), "evidence": evidence_options.applies(t)})
        return {"types": types, "sites": ctx["sites"], "sitesFor": ctx["sites_for"],
                "reportLabels": report_options.LABELS, "reportDefaults": report_options.DEFAULTS,
                "screenshotChoices": evidence_options.SCREENSHOT_CHOICES,
                "runnerEnabled": self._enabled(), "secretsAvailable": secrets_store.available(),
                "secretNames": ctx["secret_names"] or [], "ciProviders": ci_trigger.configured(),
                "user": user["name"], "role": user["role"], "perUserMode": users.per_user_mode(),
                "permissions": {p: users.allowed(user["role"], p) for p in users.PERMISSIONS}}
