"""Every test's outcome and time for each run the Console has seen — what makes "compare two runs" and the
per-test history exact instead of approximate.

<data dir>/results-cases.json: {"v": 1, "runs": [{"t": <run id>, "c": [["pkg.Class.method", code, seconds], ...]}]}
with code 0 = passed, 1 = failed, 2 = skipped. Only the newest MAX_RUNS runs are kept, at most MAX_CASES tests
per run, so the file stays around a megabyte for a few hundred tests. Runs recorded before this existed have no
entry here (the data was never kept) and fall back to the approximate failure-list comparison.

Everything read back is re-validated: a damaged or hand-edited file yields fewer runs, never a crash or a value
the UI was not meant to receive.
"""
import json
import os
import tempfile
import threading

from specs import spec_store

FILE_NAME = "results-cases.json"
MAX_RUNS = 40
MAX_CASES = 3000
PASS, FAIL, SKIP = 0, 1, 2
_lock = threading.Lock()


def path():
    return os.path.join(spec_store.data_dir(), FILE_NAME)


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v < 10 ** 7


def _clean_run(r):
    if not isinstance(r, dict) or isinstance(r.get("t"), bool) or not isinstance(r.get("t"), int) or not isinstance(r.get("c"), list):
        return None
    cases = [[c[0][:300], c[1], round(float(c[2]), 3)] for c in r["c"]
             if isinstance(c, (list, tuple)) and len(c) == 3 and isinstance(c[0], str) and c[1] in (PASS, FAIL, SKIP)
             and not isinstance(c[1], bool) and _num(c[2])]
    return {"t": r["t"], "c": cases[:MAX_CASES]}


def _load():
    try:
        with open(path(), encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    runs = data.get("runs") if isinstance(data, dict) else None
    out = [_clean_run(r) for r in runs] if isinstance(runs, list) else []
    return [r for r in out if r][-MAX_RUNS:]


def _write(runs):
    p = path()
    os.makedirs(os.path.dirname(p), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(p), prefix=".cases-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"v": 1, "runs": runs}, f, separators=(",", ":"))
        os.chmod(tmp, 0o600)
        os.replace(tmp, p)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def record(t, cases):
    """Remember `cases` ([[name, code, seconds], ...]) for run `t` unless that run is already stored."""
    run = _clean_run({"t": t, "c": cases})
    if run is None or not run["c"]:
        return False
    with _lock:
        runs = _load()
        if any(r["t"] == t for r in runs):
            return False
        runs.append(run)
        try:
            _write(runs[-MAX_RUNS:])
        except OSError:
            return False
    return True


def by_run():
    """{t: {name: (code, seconds)}} for every stored run."""
    with _lock:
        runs = _load()
    return {r["t"]: {c[0]: (c[1], c[2]) for c in r["c"]} for r in runs}
