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

import case_history
from specs import spec_store

MAX_ENTRIES = 100
MAX_FAILS_PER_RUN = 40      # names of the failing tests kept per run (feeds the stability ranking)
MAX_SLOW_PER_RUN = 15       # slowest tests kept per run (run comparison and the per-test drawer)
MAX_MSG_LEN = 160           # first error line kept per failing test
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
    for e in entries:  # hand-edited or damaged optional fields must never reach the ranking or the UI
        if "fails" in e:
            f = e["fails"]
            e["fails"] = [n[:200] for n in f if isinstance(n, str)][:MAX_FAILS_PER_RUN] if isinstance(f, list) else []
        _clean_optional(e)
    return entries[-limit:]


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0 and v < 10 ** 7


def _clean_optional(e):
    """Validate/trim `dur` (seconds), `slow` ([[name, seconds], ...]) and `msgs` ({name: first error line})."""
    if "dur" in e and not _num(e["dur"]):
        del e["dur"]
    if "slow" in e:
        s = e["slow"]
        e["slow"] = [[p[0][:200], p[1]] for p in s if isinstance(p, (list, tuple)) and len(p) == 2
                     and isinstance(p[0], str) and _num(p[1])][:MAX_SLOW_PER_RUN] if isinstance(s, list) else []
    if "msgs" in e:
        m = e["msgs"]
        e["msgs"] = {k[:200]: v[:MAX_MSG_LEN] for k, v in list(m.items())[:MAX_FAILS_PER_RUN]
                     if isinstance(k, str) and isinstance(v, str)} if isinstance(m, dict) else {}


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


def record(repo_root, surefire, details=None, cases=None, now=None):
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
        failing = surefire.get("failures")
        extra = {}
        if isinstance(details, dict):
            if _num(details.get("duration_s")) and details["duration_s"] > 0:
                extra["dur"] = round(float(details["duration_s"]), 1)
            extra["slow"] = [[f"{x.get('class', '')}.{x.get('name', '')}".lstrip("."), x.get("seconds")]
                             for x in (details.get("slowest") or []) if isinstance(x, dict)]
            extra["msgs"] = {f"{x.get('class', '')}.{x.get('name', '')}".lstrip("."): str(x.get("message") or "")
                             for x in (details.get("failure_details") or []) if isinstance(x, dict)}
        same = next((e for e in entries[-5:] if e["t"] == t and e["sig"] == sig), None)
        if same is not None:
            # Already recorded. A run recorded before durations/messages were kept is filled in once, in place.
            if extra and "slow" not in same:
                same.update(extra)
                _clean_optional(same)
                try:
                    _write(entries)
                except OSError:
                    pass
            if cases:
                case_history.record(same["t"], cases)   # no-op when this run's tests are already stored
            return entries
        entry = {"t": t, "sig": sig, "rate": round(100.0 * surefire.get("passed", 0) / total, 1),
                        "total": total, "passed": surefire.get("passed", 0),
                        "failed": surefire.get("failed", 0), "skipped": surefire.get("skipped", 0),
                        "fails": [n[:200] for n in failing if isinstance(n, str)][:MAX_FAILS_PER_RUN]
                                 if isinstance(failing, list) else []}
        entry.update(extra)
        _clean_optional(entry)
        entries.append(entry)
        if cases:
            case_history.record(entry["t"], cases)
        entries = entries[-MAX_ENTRIES:]
        try:
            _write(entries)
        except OSError:
            pass  # read-only data dir (e.g. a read-only container mount): serve the in-memory list this time
        return entries
