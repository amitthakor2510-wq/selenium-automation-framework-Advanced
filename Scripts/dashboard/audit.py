"""
Append-only change log for edits made THROUGH the dashboard — who
toggled what, when, and what it changed from/to. Lives at
target/dashboard-audit.jsonl (target/ is already gitignored, so this
never ends up in a commit), one JSON object per line, newest appended
at the end.

Not a general-purpose audit trail: it only records writes that went
through config_files.py's setters via the dashboard's own API — an edit
made by hand in a text editor, or by `mvn test -Dtests.disabled=...`,
leaves no trace here, by design (this file doesn't watch the properties
files, it's only written at the moment the dashboard itself makes a
change).

Kept deliberately simple: a flat capped list, read back and re-rendered
in full by the "Recent changes" panel and the "Undo last change" button.
No database, no rotation policy — this is a small team tool, not a
compliance log.
"""
import json
import os
import tempfile
import threading
from datetime import datetime, timezone

MAX_ENTRIES = 200  # oldest entries are dropped once the log passes this size

_lock = threading.Lock()


def _log_path(repo_root):
    return os.path.join(repo_root, "target", "dashboard-audit.jsonl")


def record(repo_root, *, actor, action, target, field, old_value, new_value):
    """
    actor: client IP (all this tool can identify a person by — there's no
           login system, see dashboard_server.py's auth model)
    action: "site" | "test" | "test-bulk" | "group" | "run-only"
    target: the site/test/group name (or a joined list, for test-bulk)
    """
    entry = {
        "at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "actor": actor,
        "action": action,
        "target": target,
        "field": field,
        "old_value": old_value,
        "new_value": new_value,
    }
    path = _log_path(repo_root)
    with _lock:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        lines = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                lines = [line for line in f.read().splitlines() if line.strip()]
        lines.append(json.dumps(entry))
        if len(lines) > MAX_ENTRIES:
            lines = lines[-MAX_ENTRIES:]
        _atomic_write(path, "\n".join(lines) + "\n")
    return entry


def recent(repo_root, limit=50):
    """Newest first."""
    path = _log_path(repo_root)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        lines = [line for line in f.read().splitlines() if line.strip()]
    entries = []
    for line in lines[-limit:]:
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    entries.reverse()
    return entries


def pop_last(repo_root):
    """
    Removes and returns the most recent entry (used by Undo), or None if
    the log is empty. The caller is responsible for actually reverting
    the config file — this only manages the log itself, so a failed
    revert doesn't leave a phantom "undone" entry removed for nothing.
    """
    path = _log_path(repo_root)
    with _lock:
        if not os.path.exists(path):
            return None
        with open(path, encoding="utf-8") as f:
            lines = [line for line in f.read().splitlines() if line.strip()]
        if not lines:
            return None
        last_line = lines.pop()
        _atomic_write(path, ("\n".join(lines) + "\n") if lines else "")
    try:
        return json.loads(last_line)
    except json.JSONDecodeError:
        return None


def _atomic_write(path, text):
    directory = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp_path = tempfile.mkstemp(prefix=".dashboard-audit-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp_path, path)
    except Exception:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise
