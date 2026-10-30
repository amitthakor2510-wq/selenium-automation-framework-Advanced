"""Server-side pass-rate history for the Console's trend line.

One entry per distinct test run, kept in <data dir>/results-history.json (the same DASHBOARD_DATA_DIR the
runner and templates use), so the trend is shared by every browser and survives clearing site data.

An entry is stamped with the run's own time (the newest surefire report file), not the moment somebody
happened to open the page, and is keyed by (that time, the pass/fail counts): polling an unchanged run
never adds a row, while a re-run that happens to give identical counts still does.

History is best-effort. A missing, corrupt or unwritable file never breaks /api/results: the caller just
gets whatever could be read.
"""
import glob
import json
import os
import tempfile
import threading

from specs import spec_store

MAX_ENTRIES = 100
FILE_NAME = "results-history.json"
_lock = threading.Lock()


def history_path():
    return os.path.join(spec_store.data_dir(), FILE_NAME)


def _run_time(repo_root):
    """Epoch seconds of the newest surefire report, or None when there are none."""
    files = glob.glob(os.path.join(repo_root, "target", "surefire-reports", "*.xml"))
    try:
        return int(max(os.path.getmtime(f) for f in files)) if files else None
    except OSError:
        return None


def _valid(entry):
    return (isinstance(entry, dict) and isinstance(entry.get("t"), int) and isinstance(entry.get("sig"), str)
            and isinstance(entry.get("rate"), (int, float)) and not isinstance(entry.get("rate"), bool)
            and 0 <= entry["rate"] <= 100)


def read(limit=MAX_ENTRIES):
    """Newest-last list of valid entries; [] when the file is missing or unreadable."""
    try:
        with open(history_path(), encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    entries = [e for e in data if _valid(e)] if isinstance(data, list) else []
    return entries[-limit:]


def _write(entries):
    path = history_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".hist-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(entries, f, separators=(",", ":"))
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def record(repo_root, surefire, now=None):
    """Add this run if it is new, then return the history (newest last)."""
    total = surefire.get("total") if isinstance(surefire, dict) else 0
    if not isinstance(total, int) or total <= 0:
        return read()
    t = _run_time(repo_root)
    if t is None:
        import time
        t = int(now if now is not None else time.time())
    sig = f"{total}/{surefire.get('passed', 0)}/{surefire.get('failed', 0)}/{surefire.get('skipped', 0)}"
    with _lock:
        entries = read()
        if any(e["t"] == t and e["sig"] == sig for e in entries[-5:]):
            return entries
        entries.append({"t": t, "sig": sig, "rate": round(100.0 * surefire.get("passed", 0) / total, 1),
                        "total": total, "passed": surefire.get("passed", 0),
                        "failed": surefire.get("failed", 0), "skipped": surefire.get("skipped", 0)})
        entries = entries[-MAX_ENTRIES:]
        try:
            _write(entries)
        except OSError:
            pass  # read-only data dir (e.g. a read-only container mount): serve the in-memory list this time
        return entries
