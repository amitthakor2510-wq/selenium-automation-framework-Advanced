"""Release-readiness verdict for the latest recorded test run.

A pure function over data the Console already reads (surefire totals, coverage summary, run history, flaky
report), so the same verdict is shown on the Overview tab and is available to CI as GET /api/quality-gate.

Every check is one of:
  pass    - the measurement meets its limit
  fail    - the measurement breaks its limit
  skip    - the limit is switched off (0 / absent)
  unknown - there is nothing to measure yet (no run, no coverage report, too little history)

The overall status is "fail" if any check fails, "unknown" if there is no run at all, otherwise "pass".
"unknown" is never reported as "pass": a gate that passes because nothing ran would be worse than no gate.
"""
import statistics

DEFAULTS = {"min_pass": 95.0, "max_failed": 0, "min_coverage": None, "max_slowdown": 50.0, "max_flaky": 0}
MIN_TIMED_RUNS = 4     # slowdown needs a few timed runs before a median means anything
MEDIAN_WINDOW = 10     # ... measured against the previous N runs


def _num(value, default=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    return value


def parse_limits(query, user_prefs=None):
    """Limits from the query string (lists as parse_qs gives them), else the user's alert preferences, else
    DEFAULTS. Raises ValueError for a value that is not a number in range."""
    limits = dict(DEFAULTS)
    prefs = user_prefs or {}
    if _num(prefs.get("alert_pass_pct"), 0) > 0:
        limits["min_pass"] = float(prefs["alert_pass_pct"])
    if _num(prefs.get("alert_coverage_pct"), 0) > 0:
        limits["min_coverage"] = float(prefs["alert_coverage_pct"])
    if _num(prefs.get("alert_slowdown_pct"), 0) > 0:
        limits["max_slowdown"] = float(prefs["alert_slowdown_pct"])
    ranges = {"min_pass": (0, 100), "max_failed": (0, 1_000_000), "min_coverage": (0, 100),
              "max_slowdown": (0, 10_000), "max_flaky": (0, 1_000_000)}
    for key, (lo, hi) in ranges.items():
        raw = (query or {}).get(key, [None])[0]
        if raw is None or raw == "":
            continue
        try:
            value = float(raw)
        except ValueError:
            raise ValueError(f"'{key}' must be a number")
        if value != value or not lo <= value <= hi:   # NaN or out of range
            raise ValueError(f"'{key}' must be between {lo} and {hi}")
        limits[key] = int(value) if key in ("max_failed", "max_flaky") else value
    return limits


def _check(cid, label, status, actual, limit, detail):
    return {"id": cid, "label": label, "status": status, "actual": actual, "limit": limit, "detail": detail}


def evaluate(summary, coverage, history, flaky, limits):
    """summary: results.surefire_summary(); coverage: results.coverage_summary() or None; history: newest-last
    results_history entries; flaky: results.flaky_tests() or None; limits: parse_limits() output."""
    summary = summary or {}
    total = _num(summary.get("total"), 0)
    failed = _num(summary.get("failed"), 0)
    passed = _num(summary.get("passed"), 0)
    checks = []

    if total <= 0:
        return {"status": "unknown", "summary": "No test run found yet - run the tests, then reopen this page.",
                "checks": [], "limits": limits}

    rate = round(100.0 * passed / total, 1)
    checks.append(_check("pass_rate", "Pass rate", "pass" if rate >= limits["min_pass"] else "fail", rate,
                         limits["min_pass"], f"{passed} of {total} tests passed ({rate}%); the limit is {limits['min_pass']:g}%."))
    checks.append(_check("failed", "Failing tests", "pass" if failed <= limits["max_failed"] else "fail", failed,
                         limits["max_failed"], f"{failed} failing; at most {limits['max_failed']} allowed."))

    min_cov = limits.get("min_coverage")
    cov_pct = _num((coverage or {}).get("line_pct"))
    if min_cov is None and coverage:
        min_cov = _num(coverage.get("threshold_pct"))   # the build's own gate (pom.xml) when no limit is given
    if min_cov is None or min_cov <= 0:
        checks.append(_check("coverage", "Core coverage", "skip", cov_pct, None, "No coverage limit is set."))
    elif cov_pct is None:
        checks.append(_check("coverage", "Core coverage", "unknown", None, min_cov, "No coverage report found for this run."))
    else:
        checks.append(_check("coverage", "Core coverage", "pass" if cov_pct >= min_cov else "fail", cov_pct, min_cov,
                             f"Line coverage of the core package is {cov_pct:g}%; the limit is {min_cov:g}%."))

    max_slow = limits.get("max_slowdown")
    if not max_slow or max_slow <= 0:
        checks.append(_check("slowdown", "Run time", "skip", None, None, "No slowdown limit is set."))
    else:
        timed = [e["dur"] for e in (history or []) if _num(e.get("dur"), 0) > 0]
        if len(timed) < MIN_TIMED_RUNS:
            checks.append(_check("slowdown", "Run time", "unknown", None, max_slow,
                                 f"Needs {MIN_TIMED_RUNS} timed runs in the history; have {len(timed)}."))
        else:
            latest = timed[-1]
            median = statistics.median(timed[-(MEDIAN_WINDOW + 1):-1])
            pct = round(100.0 * (latest - median) / median, 1) if median > 0 else 0.0
            checks.append(_check("slowdown", "Run time", "pass" if pct < max_slow else "fail", pct, max_slow,
                                 f"The last run took {latest:g}s, {pct:+g}% against the recent median of {median:g}s "
                                 f"(limit: under {max_slow:g}% slower)."))

    max_flaky = limits.get("max_flaky")
    flaky_count = _num((flaky or {}).get("flaky_count"))
    if max_flaky is None:
        checks.append(_check("flaky", "Flaky tests", "skip", flaky_count, None, "No flaky-test limit is set."))
    elif flaky_count is None:
        checks.append(_check("flaky", "Flaky tests", "unknown", None, max_flaky,
                             "No flaky-test report yet (it is produced by CI history runs)."))
    else:
        checks.append(_check("flaky", "Flaky tests", "pass" if flaky_count <= max_flaky else "fail", flaky_count,
                             max_flaky, f"{flaky_count} flaky test(s); at most {max_flaky} allowed."))

    failing = [c for c in checks if c["status"] == "fail"]
    status = "fail" if failing else "pass"
    text = (f"Blocked by {len(failing)} check{'s' if len(failing) != 1 else ''}: "
            + ", ".join(c["label"].lower() for c in failing) + ".") if failing else "All active checks pass."
    return {"status": status, "summary": text, "checks": checks, "limits": limits}
