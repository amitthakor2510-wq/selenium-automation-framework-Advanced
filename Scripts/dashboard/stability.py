"""Rank tests by how unstable they are, for the Console's Results tab.

Two sources, merged (CI wins when both know a test):
  * CI history  — target/flaky-tests.json, each test with a `recent_statuses` list (oldest -> newest);
  * local history — the failing tests the Console recorded for every run it saw (results_history `fails`).
    A test that is not in a run's failure list counts as "not failing" there; the Console cannot tell
    "passed" from "was not part of that run", so local numbers are a good guide, not a guarantee.

Each ranked entry: {test, kind, fails, runs, flips, rate, recent, source}
  kind: flaky      — status flipped two or more times (fail<->pass)
        new        — started failing and has not recovered (or too few runs to call it consistent)
        consistent — failing in every one of at least three recent runs
        recovered  — failed earlier, passing now
"""

MAX_RESULTS = 50
RECENT_SHOWN = 12
_KIND_ORDER = {"flaky": 0, "new": 1, "consistent": 2, "recovered": 3}


def classify(statuses):
    """(kind, fails, runs, flips) for oldest->newest statuses, or None when the test never failed."""
    seq = [s for s in statuses if s in ("pass", "fail")]
    fails = seq.count("fail")
    if not fails:
        return None
    flips = sum(1 for a, b in zip(seq, seq[1:]) if a != b)
    runs = len(seq)
    if fails == runs:
        kind = "consistent" if runs >= 3 else "new"
    elif flips >= 2:
        kind = "flaky"
    elif seq[-1] == "fail":
        kind = "new"
    else:
        kind = "recovered"
    return kind, fails, runs, flips


def _entry(test, statuses, source):
    c = classify(statuses)
    if c is None:
        return None
    kind, fails, runs, flips = c
    return {"test": test, "kind": kind, "fails": fails, "runs": runs, "flips": flips,
            "rate": round(100.0 * fails / runs, 1), "recent": list(statuses)[-RECENT_SHOWN:], "source": source}


def _from_ci(flaky):
    out = []
    items = flaky.get("flaky") if isinstance(flaky, dict) else None
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict) or not isinstance(item.get("test"), str):
            continue
        statuses = [s for s in (item.get("recent_statuses") or []) if isinstance(s, str)]
        if not statuses:  # counts only: order unknown, so the best honest reading is "flaky if both outcomes exist"
            p, f = item.get("pass_count") or 0, item.get("fail_count") or 0
            if not (isinstance(p, int) and isinstance(f, int) and p > 0 and f > 0):
                continue
            out.append({"test": item["test"][:200], "kind": "flaky", "fails": f, "runs": p + f, "flips": 0,
                        "rate": round(100.0 * f / (p + f), 1), "recent": [], "source": "ci"})
            continue
        e = _entry(item["test"][:200], statuses, "ci")
        if e:
            out.append(e)
    return out


def _from_local(history):
    runs = [e["fails"] for e in history if isinstance(e, dict) and isinstance(e.get("fails"), list)]
    if not runs:
        return []
    names = sorted({n for fails in runs for n in fails if isinstance(n, str)})
    out = []
    for name in names:
        e = _entry(name, ["fail" if name in fails else "pass" for fails in runs], "local")
        if e:
            out.append(e)
    return out


def rank(history, flaky):
    """Ranked, de-duplicated list (worst first), capped at MAX_RESULTS."""
    merged = {e["test"]: e for e in _from_local(history)}
    merged.update({e["test"]: e for e in _from_ci(flaky)})
    ranked = sorted(merged.values(), key=lambda e: (_KIND_ORDER[e["kind"]], -e["flips"], -e["rate"], e["test"]))
    return ranked[:MAX_RESULTS]
