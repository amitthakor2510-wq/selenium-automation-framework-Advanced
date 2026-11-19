"""Questions the Results tab asks of the recorded run history: "what changed between these two runs?" and
"how has this one test behaved?". Pure functions over results_history entries (newest last); no I/O.

Two levels of per-test data:
  * EXACT — case_history keeps every test's outcome (passed / failed / skipped) and time for the runs seen since
    it was introduced. When both runs have it, the comparison is test by test and says `exact: true`.
  * APPROXIMATE — older runs only have the failing tests (`fails`), their first error line (`msgs`) and the
    slowest tests with seconds (`slow`). A test not in a run's failure list is treated as not failing and a time is
    known only when the test was among that run's slowest. The output says so (`exact: false`) instead of
    inventing numbers.
"""
import case_history as ch
import stability

SLOWER_MIN_SECONDS = 2.0    # a test must be this much slower ...
SLOWER_MIN_RATIO = 1.5      # ... and this many times slower to be listed
MAX_LIST = 15
MAX_RUNS_SHOWN = 30


def _find(history, t):
    return next((e for e in history if e.get("t") == t), None)


def _brief(e):
    return {k: e.get(k) for k in ("t", "rate", "total", "passed", "failed", "skipped", "dur")}


def compare(history, a_t, b_t, cases=None):
    """Run A (the baseline, older) against run B (the newer). Raises ValueError for unknown or identical runs."""
    if not isinstance(a_t, int) or not isinstance(b_t, int):
        raise ValueError("both run ids must be integers")
    a, b = _find(history, a_t), _find(history, b_t)
    if a is None or b is None:
        raise ValueError("unknown run: it may have rolled out of the recorded history")
    if a is b:
        raise ValueError("pick two different runs")
    ca, cb = (cases or {}).get(a_t), (cases or {}).get(b_t)
    exact = bool(ca) and bool(cb)
    out = {"a": _brief(a), "b": _brief(b), "per_test": exact or ("fails" in a and "fails" in b), "exact": exact,
           "new_failures": [], "fixed": [], "still_failing": [], "no_longer_run": [], "slower": [], "faster": [],
           "duration_pct": None}
    sa = sb = None
    if exact:
        msgs = b.get("msgs") or {}
        new, fixed, still, gone = [], [], [], []
        for name, (code, _s) in cb.items():
            if code == ch.FAIL:
                prev = ca.get(name)
                if prev is None:
                    new.append({"test": name, "message": msgs.get(name, ""), "was": "not in the baseline run"})
                elif prev[0] == ch.FAIL:
                    still.append(name)
                else:
                    new.append({"test": name, "message": msgs.get(name, ""), "was": "passing" if prev[0] == ch.PASS else "skipped"})
        for name, (code, _s) in ca.items():
            if code == ch.FAIL:
                now = cb.get(name)
                if now is not None and now[0] == ch.PASS:
                    fixed.append(name)
                elif now is None or now[0] == ch.SKIP:
                    gone.append(name)
        new.sort(key=lambda x: x["test"])
        out["new_failures"] = new[:MAX_LIST * 3]
        out["fixed"] = sorted(fixed)[:MAX_LIST * 3]
        out["still_failing"] = sorted(still)[:MAX_LIST * 3]
        out["no_longer_run"] = sorted(gone)[:MAX_LIST * 3]
        out["counts"] = {"new_failures": len(new), "fixed": len(fixed), "still_failing": len(still), "no_longer_run": len(gone)}
        sa, sb = {n: v[1] for n, v in ca.items()}, {n: v[1] for n, v in cb.items()}
    elif out["per_test"]:
        fa, fb = set(a["fails"]), set(b["fails"])
        msgs = b.get("msgs") or {}
        out["new_failures"] = [{"test": n, "message": msgs.get(n, "")} for n in sorted(fb - fa)][:MAX_LIST * 3]
        out["fixed"] = sorted(fa - fb)[:MAX_LIST * 3]
        out["still_failing"] = sorted(fa & fb)[:MAX_LIST * 3]
        out["counts"] = {"new_failures": len(fb - fa), "fixed": len(fa - fb), "still_failing": len(fa & fb)}
    if sa is None:
        sa, sb = dict(a.get("slow") or []), dict(b.get("slow") or [])
    deltas = []
    for name in set(sa) & set(sb):
        x, y = sa[name], sb[name]
        if y - x >= SLOWER_MIN_SECONDS and y >= x * SLOWER_MIN_RATIO:
            deltas.append(("slower", {"test": name, "a": x, "b": y, "delta": round(y - x, 2)}))
        elif x - y >= SLOWER_MIN_SECONDS and x >= y * SLOWER_MIN_RATIO:
            deltas.append(("faster", {"test": name, "a": x, "b": y, "delta": round(y - x, 2)}))
    out["slower"] = sorted((d for k, d in deltas if k == "slower"), key=lambda d: -d["delta"])[:MAX_LIST]
    out["faster"] = sorted((d for k, d in deltas if k == "faster"), key=lambda d: d["delta"])[:MAX_LIST]
    if isinstance(a.get("dur"), (int, float)) and a["dur"] > 0 and isinstance(b.get("dur"), (int, float)):
        out["duration_pct"] = round(100.0 * (b["dur"] - a["dur"]) / a["dur"], 1)
    return out


def test_history(history, name, cases=None):
    """How one test has behaved across the runs that recorded per-test data. Raises ValueError for a bad name."""
    if not isinstance(name, str) or not name.strip() or len(name) > 300:
        raise ValueError("a test name is required")
    cases = cases or {}
    runs = []
    for e in history:
        c = cases.get(e["t"])
        if c:   # exact: this run's every test is known
            hit = c.get(name)
            rec = {"t": e["t"], "status": "absent" if hit is None else ("fail" if hit[0] == ch.FAIL else "skip" if hit[0] == ch.SKIP else "pass"),
                   "exact": True}
            if hit is not None and hit[1] > 0:
                rec["seconds"] = hit[1]
        elif isinstance(e.get("fails"), list):
            rec = {"t": e["t"], "status": "fail" if name in e["fails"] else "pass"}
            secs = dict(e.get("slow") or []).get(name)
            if secs is not None:
                rec["seconds"] = secs
        else:
            continue
        msg = (e.get("msgs") or {}).get(name)
        if msg:
            rec["message"] = msg
        runs.append(rec)
    runs = runs[-MAX_RUNS_SHOWN:]
    statuses = [r["status"] for r in runs]
    c = stability.classify(statuses)
    times = [r["seconds"] for r in runs if "seconds" in r]
    failed = [r for r in runs if r["status"] == "fail"]
    return {
        "test": name,
        "known": bool(failed or times),
        "runs": runs,
        "kind": c[0] if c else None,
        "fails": statuses.count("fail"),
        "total_runs": len(runs),
        "flips": c[3] if c else 0,
        "last_error": next((r["message"] for r in reversed(failed) if r.get("message")), ""),
        "last_failed_t": failed[-1]["t"] if failed else None,
        "avg_seconds": round(sum(times) / len(times), 2) if times else None,
        "max_seconds": round(max(times), 2) if times else None,
        "timed_runs": len(times),
        "exact_runs": sum(1 for r in runs if r.get("exact")),
    }
