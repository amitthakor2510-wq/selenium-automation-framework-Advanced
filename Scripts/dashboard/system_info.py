"""Read-only facts about the running Console and the repo it points at, for the Overview page.

Deliberately subprocess-free (the server's "never runs git/Maven in its default configuration" guarantee
still holds): the branch and commit are read straight from .git/HEAD and its ref files. Every lookup is
best-effort and degrades to None, so a source archive with no .git, or a worktree layout we do not
understand, just shows "unknown" instead of failing the page.
"""
import os
import platform
import re
import time

STARTED_AT = time.time()

_SHA = re.compile(r"^[0-9a-f]{7,64}$")

# Report folders a run can leave behind, shown on the Overview with whether they exist yet.
REPORT_DIRS = (
    ("Allure results", "target/allure-results"),
    ("Allure report", "target/allure-report"),
    ("Extent reports", "target/extent-reports"),
    ("Surefire reports", "target/surefire-reports"),
    ("JaCoCo coverage", "target/site/jacoco"),
    ("PDF report", "target/pdf-report"),
)

# How to open each one on this machine. The Console never serves report HTML itself: a report is generated from
# page content the tests visited, and serving it from the Console's own origin would put that untrusted markup
# next to the Console's authenticated session. These are commands/paths to copy, not links.
OPEN_HINTS = {
    "target/allure-results": "allure serve target/allure-results",
    "target/allure-report": "allure open target/allure-report",
    "target/extent-reports": "open the newest .html file in target/extent-reports",
    "target/surefire-reports": None,
    "target/site/jacoco": "open target/site/jacoco/index.html in a browser",
    "target/pdf-report": "open the newest .pdf file in target/pdf-report",
}

_GITHUB_REMOTE = re.compile(r"github\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?\s*$")
_MAX_SCAN = 2000


def _read(path, limit=4096):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read(limit).strip()
    except OSError:
        return None


def _git_dir(repo_root):
    """The real git directory: .git itself, or the target of a `gitdir:` pointer file (worktrees, submodules)."""
    dot_git = os.path.join(repo_root, ".git")
    if os.path.isdir(dot_git):
        return dot_git
    pointer = _read(dot_git)
    if pointer and pointer.startswith("gitdir:"):
        target = pointer[len("gitdir:"):].strip()
        return target if os.path.isabs(target) else os.path.normpath(os.path.join(repo_root, target))
    return None


def _packed_ref(git_dir, ref):
    text = _read(os.path.join(git_dir, "packed-refs"), limit=2_000_000) or ""
    for line in text.splitlines():
        parts = line.split(" ", 1)
        if len(parts) == 2 and parts[1].strip() == ref and _SHA.match(parts[0]):
            return parts[0]
    return None


def git_info(repo_root):
    """{'branch': str|None, 'commit': short sha|None, 'detached': bool} — all None when not a git checkout."""
    info = {"branch": None, "commit": None, "detached": False}
    git_dir = _git_dir(repo_root)
    if not git_dir:
        return info
    head = _read(os.path.join(git_dir, "HEAD"))
    if not head:
        return info
    if head.startswith("ref:"):
        ref = head[4:].strip()
        info["branch"] = ref[len("refs/heads/"):] if ref.startswith("refs/heads/") else ref
        sha = _read(os.path.join(git_dir, *ref.split("/")))
        if not (sha and _SHA.match(sha)):
            sha = _packed_ref(git_dir, ref)
    else:
        info["detached"] = True
        sha = head if _SHA.match(head) else None
    info["commit"] = sha[:8] if sha else None
    return info


def _newest_mtime(folder):
    """Epoch seconds of the newest file under `folder` (bounded scan), or None when it holds no files."""
    newest, seen = None, 0
    for base, _dirs, files in os.walk(folder):
        for name in files:
            seen += 1
            if seen > _MAX_SCAN:
                return newest
            try:
                m = os.path.getmtime(os.path.join(base, name))
            except OSError:
                continue
            newest = m if newest is None or m > newest else newest
    return int(newest) if newest is not None else None


def reports_present(repo_root):
    out = []
    for label, rel in REPORT_DIRS:
        full = os.path.join(repo_root, *rel.split("/"))
        present = os.path.isdir(full) and bool(os.listdir(full))
        out.append({"name": label, "path": rel, "present": present,
                    "modified": _newest_mtime(full) if present else None, "open_hint": OPEN_HINTS.get(rel)})
    return out


def _safe_http_url(value):
    v = (value or "").strip()
    return v if re.match(r"^https?://[A-Za-z0-9.-]+(:\d+)?(/[A-Za-z0-9._~%!$&'()*+,;=:@/-]*)?$", v) else None


def published_reports_url(repo_root, env=None):
    """{'url', 'source'} for the CI-published reports page, or None.

    DASHBOARD_REPORTS_URL (an http/https URL) wins. Otherwise, for a github.com `origin`, the conventional
    GitHub Pages address (https://<owner>.github.io/<repo>/) — read from .git/config, no subprocess. It is a
    best guess: it only works if the project's Pages site is enabled, which the label in the UI says.
    """
    env = os.environ if env is None else env
    explicit = _safe_http_url(env.get("DASHBOARD_REPORTS_URL"))
    if explicit:
        return {"url": explicit, "source": "env"}
    git_dir = _git_dir(repo_root)
    text = _read(os.path.join(git_dir, "config"), limit=200_000) if git_dir else None
    if not text:
        return None
    in_origin = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            in_origin = stripped.replace(" ", "") == '[remote"origin"]'
        elif in_origin and stripped.startswith("url"):
            m = _GITHUB_REMOTE.search(stripped.split("=", 1)[-1].strip())
            if m:
                owner, repo = m.group(1), m.group(2)
                host = f"{owner.lower()}.github.io"
                url = f"https://{host}/" if repo.lower() == host.lower() else f"https://{host}/{repo}/"
                return {"url": url, "source": "github"}
    return None


def last_run_epoch(repo_root):
    """Epoch seconds of the newest surefire report, or None."""
    folder = os.path.join(repo_root, "target", "surefire-reports")
    try:
        times = [os.path.getmtime(os.path.join(folder, n)) for n in os.listdir(folder) if n.endswith(".xml")]
    except OSError:
        return None
    return int(max(times)) if times else None


def snapshot(repo_root, *, user, runner_enabled, per_user, host, port):
    """The payload for GET /api/system. `user` is the authenticated {'name','role'} dict."""
    now = time.time()
    return {
        "server": {
            "python": platform.python_version(),
            "platform": platform.system(),
            "started_at": int(STARTED_AT),
            "uptime_s": int(now - STARTED_AT),
            "host": host,
            "port": port,
        },
        "repo": {"name": os.path.basename(os.path.abspath(repo_root)), **git_info(repo_root)},
        "runner": {"enabled": bool(runner_enabled)},
        "auth": {"per_user": bool(per_user), "user": (user or {}).get("name"), "role": (user or {}).get("role")},
        "reports": reports_present(repo_root),
        "reports_url": published_reports_url(repo_root),
        "last_run": last_run_epoch(repo_root),
        "now": int(now),
    }
