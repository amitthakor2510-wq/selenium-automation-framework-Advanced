"""
Start and poll a pipeline run on GitHub Actions, GitLab CI or Jenkins.

This triggers the repo's OWN pipeline (its checked-in suites) with a few parameters — it can't
ship a wizard-built scenario to CI, because that scenario only exists in the local run
workspace. The wizard says so on screen.

Configuration is admin-only and comes from the dashboard's environment (never from the
browser, never stored in a spec):

  GitHub : DASHBOARD_CI_GITHUB_REPO=owner/repo  DASHBOARD_CI_GITHUB_TOKEN=...  [DASHBOARD_CI_GITHUB_WORKFLOW=github-ci.yml]
  GitLab : DASHBOARD_CI_GITLAB_URL=https://gitlab.example  DASHBOARD_CI_GITLAB_PROJECT=<id or group%2Fproject>
           DASHBOARD_CI_GITLAB_TOKEN=...  [DASHBOARD_CI_GITLAB_CA_FILE=/path/ca.crt]
  Jenkins: DASHBOARD_CI_JENKINS_URL=https://jenkins.example  DASHBOARD_CI_JENKINS_JOB=name
           DASHBOARD_CI_JENKINS_USER=...  DASHBOARD_CI_JENKINS_TOKEN=...
  all    : DASHBOARD_CI_REF=main  (default branch name for the run)

Only the parameters in ALLOWED_PARAMS can be sent, each validated.
"""
import base64
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

PROVIDERS = ("github", "gitlab", "jenkins")
ALLOWED_PARAMS = {
    "suite_type": re.compile(r"^(regression|smoke)$"),
    "browser": re.compile(r"^(chrome|firefox|edge)$"),
    "headless": re.compile(r"^(true|false)$"),
    "site": re.compile(r"^([A-Za-z0-9_]{1,40}|ALL)$"),
}
_REF_RE = re.compile(r"^[A-Za-z0-9._/-]{1,100}$")
TIMEOUT = 20


class CiError(Exception):
    pass


def configured(environ=None):
    """Which providers have all their settings present."""
    e = os.environ if environ is None else environ
    need = {"github": ("DASHBOARD_CI_GITHUB_REPO", "DASHBOARD_CI_GITHUB_TOKEN"),
            "gitlab": ("DASHBOARD_CI_GITLAB_URL", "DASHBOARD_CI_GITLAB_PROJECT", "DASHBOARD_CI_GITLAB_TOKEN"),
            "jenkins": ("DASHBOARD_CI_JENKINS_URL", "DASHBOARD_CI_JENKINS_JOB",
                        "DASHBOARD_CI_JENKINS_USER", "DASHBOARD_CI_JENKINS_TOKEN")}
    return [p for p, keys in need.items() if all(e.get(k) for k in keys)]


def clean_params(params):
    out = {}
    for k, v in (params or {}).items():
        rx = ALLOWED_PARAMS.get(k)
        v = str(v).lower() if isinstance(v, bool) else str(v)
        if rx is None:
            raise CiError(f"parameter '{k}' can't be sent to CI")
        if not rx.match(v):
            raise CiError(f"value for '{k}' is not allowed")
        out[k] = v
    return out


def http_request(method, url, headers=None, body=None, ca_file=None):
    """-> (status, headers_dict, parsed_json_or_None). Isolated so tests can stub it."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=dict(headers or {}))
    if data is not None:
        req.add_header("Content-Type", "application/json")
    ctx = ssl.create_default_context(cafile=ca_file) if (ca_file and url.startswith("https")) else None
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=ctx) as resp:
            raw = resp.read()
            status, hdrs = resp.status, {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        raise CiError(f"CI server answered HTTP {e.code}") from None
    except (urllib.error.URLError, OSError) as e:
        raise CiError(f"could not reach the CI server: {getattr(e, 'reason', e)}") from None
    try:
        return status, hdrs, (json.loads(raw) if raw else None)
    except ValueError:
        return status, hdrs, None


def _ref(environ, ref):
    ref = ref or environ.get("DASHBOARD_CI_REF", "main")
    if not _REF_RE.match(ref):
        raise CiError("branch name is not allowed")
    return ref


def start(provider, params, ref=None, environ=None):
    """Trigger a run. Returns a handle dict to pass to poll()."""
    e = os.environ if environ is None else environ
    if provider not in configured(e):
        raise CiError(f"{provider} is not configured on this dashboard")
    p, ref = clean_params(params), _ref(e, ref)
    now = time.time()
    if provider == "github":
        repo, wf = e["DASHBOARD_CI_GITHUB_REPO"], e.get("DASHBOARD_CI_GITHUB_WORKFLOW", "github-ci.yml")
        if not re.match(r"^[\w.-]+/[\w.-]+$", repo) or not re.match(r"^[\w.-]+\.ya?ml$", wf):
            raise CiError("GitHub repo/workflow setting is invalid")
        inputs = {k: v for k, v in p.items() if k in ("suite_type", "browser", "headless")}
        http_request("POST", f"https://api.github.com/repos/{repo}/actions/workflows/{wf}/dispatches",
                     _gh_headers(e), {"ref": ref, "inputs": inputs})
        return {"provider": provider, "repo": repo, "workflow": wf, "ref": ref, "after": now - 5}
    if provider == "gitlab":
        base, proj = e["DASHBOARD_CI_GITLAB_URL"].rstrip("/"), e["DASHBOARD_CI_GITLAB_PROJECT"]
        variables = [{"key": k.upper(), "value": v} for k, v in p.items() if k != "site"]
        _, _, doc = http_request("POST", f"{base}/api/v4/projects/{urllib.parse.quote(proj, safe='%')}/pipeline",
                                 {"PRIVATE-TOKEN": e["DASHBOARD_CI_GITLAB_TOKEN"]},
                                 {"ref": ref, "variables": variables}, e.get("DASHBOARD_CI_GITLAB_CA_FILE"))
        if not isinstance(doc, dict) or "id" not in doc:
            raise CiError("GitLab did not return a pipeline id")
        return {"provider": provider, "pipeline": doc["id"], "url": doc.get("web_url", "")}
    base, job = e["DASHBOARD_CI_JENKINS_URL"].rstrip("/"), urllib.parse.quote(e["DASHBOARD_CI_JENKINS_JOB"], safe="")
    q = urllib.parse.urlencode({k.upper(): v for k, v in p.items()})
    _, hdrs, _ = http_request("POST", f"{base}/job/{job}/buildWithParameters?{q}", _jenkins_headers(e))
    loc = hdrs.get("location", "")
    if not loc:
        raise CiError("Jenkins did not return a queue location")
    return {"provider": provider, "queue": loc.rstrip("/")}


def _gh_headers(e):
    return {"Authorization": "Bearer " + e["DASHBOARD_CI_GITHUB_TOKEN"], "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "framework-dashboard"}


def _jenkins_headers(e):
    cred = base64.b64encode(f"{e['DASHBOARD_CI_JENKINS_USER']}:{e['DASHBOARD_CI_JENKINS_TOKEN']}".encode()).decode()
    return {"Authorization": "Basic " + cred}


_GL_STATES = {"success": "passed", "failed": "failed", "canceled": "cancelled", "skipped": "cancelled",
              "running": "running"}


def poll(handle, environ=None):
    """-> {"state": queued|running|passed|failed|cancelled, "url": str}"""
    e = os.environ if environ is None else environ
    provider = handle.get("provider")
    if provider == "github":
        repo, wf = handle["repo"], handle["workflow"]
        _, _, doc = http_request("GET", f"https://api.github.com/repos/{repo}/actions/workflows/{wf}/runs"
                                 f"?event=workflow_dispatch&branch={urllib.parse.quote(handle['ref'])}&per_page=10", _gh_headers(e))
        for r in (doc or {}).get("workflow_runs", []):
            created = time.mktime(time.strptime(r["created_at"], "%Y-%m-%dT%H:%M:%SZ")) - time.timezone
            if created >= handle["after"]:
                if r["status"] != "completed":
                    return {"state": "running" if r["status"] == "in_progress" else "queued", "url": r.get("html_url", "")}
                return {"state": "passed" if r.get("conclusion") == "success" else
                        "cancelled" if r.get("conclusion") == "cancelled" else "failed", "url": r.get("html_url", "")}
        return {"state": "queued", "url": ""}
    if provider == "gitlab":
        base, proj = e["DASHBOARD_CI_GITLAB_URL"].rstrip("/"), e["DASHBOARD_CI_GITLAB_PROJECT"]
        _, _, doc = http_request("GET", f"{base}/api/v4/projects/{urllib.parse.quote(proj, safe='%')}/pipelines/{int(handle['pipeline'])}",
                                 {"PRIVATE-TOKEN": e["DASHBOARD_CI_GITLAB_TOKEN"]}, None, e.get("DASHBOARD_CI_GITLAB_CA_FILE"))
        st = (doc or {}).get("status", "")
        return {"state": _GL_STATES.get(st, "queued"), "url": (doc or {}).get("web_url", handle.get("url", ""))}
    if provider == "jenkins":
        hdr = _jenkins_headers(e)
        base = e["DASHBOARD_CI_JENKINS_URL"].rstrip("/")
        if not handle["queue"].startswith(base):
            raise CiError("unexpected Jenkins queue location")
        _, _, q = http_request("GET", handle["queue"] + "/api/json", hdr)
        exe = (q or {}).get("executable")
        if not exe:
            return {"state": "cancelled" if (q or {}).get("cancelled") else "queued", "url": ""}
        if not exe["url"].startswith(base):
            raise CiError("unexpected Jenkins build location")
        _, _, b = http_request("GET", exe["url"].rstrip("/") + "/api/json", hdr)
        if (b or {}).get("building", True):
            return {"state": "running", "url": exe["url"]}
        res = (b or {}).get("result")
        return {"state": "passed" if res == "SUCCESS" else "cancelled" if res == "ABORTED" else "failed", "url": exe["url"]}
    raise CiError("unknown provider")
