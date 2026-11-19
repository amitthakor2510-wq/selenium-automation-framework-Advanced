"""
Read-only view over whatever test-result artifacts already exist under
target/ from the most recent local `mvn test` run(s). Never runs Maven and never
touches the repo. The only thing it writes is the pass-rate trend file in the dashboard's
own data dir (results_history.py); config editing lives in config_files.py.

Every file here is optional: a fresh checkout with no run yet returns
empty/zero results rather than erroring, so the dashboard is useful
before the first test run too (just shows "no data yet").
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

import results_history
import stability

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPTS_DIR = os.path.join(_THIS_DIR, "..", "..", ".github", "workflows", "scripts")
sys.path.insert(0, os.path.abspath(_SCRIPTS_DIR))
try:
    from _junit_utils import parse_surefire_dir  # reuse the one parser the CI scripts already trust
except ImportError:
    parse_surefire_dir = None  # repo layout changed / file moved — degrade gracefully below


def _read_json(path):
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return None


def surefire_summary(repo_root):
    """{'total','passed','failed','skipped','by_class': [...], 'failures': [...]}"""
    surefire_dir = os.path.join(repo_root, "target", "surefire-reports")
    if parse_surefire_dir is None:
        return {"total": 0, "passed": 0, "failed": 0, "skipped": 0, "by_class": [], "failures": [],
                "note": "_junit_utils.py not found — CI scripts directory may have moved"}
    results, totals = parse_surefire_dir(surefire_dir)

    by_class = {}
    for key, status in results.items():
        cls, _, method = key.rpartition(".")
        cls = cls or key
        bucket = by_class.setdefault(cls, {"class": cls, "total": 0, "passed": 0, "failed": 0, "skipped": 0})
        bucket["total"] += 1
        bucket[{"pass": "passed", "fail": "failed", "skip": "skipped"}[status]] += 1

    failures = sorted(k for k, v in results.items() if v == "fail")
    return {
        **totals,
        "by_class": sorted(by_class.values(), key=lambda b: (-b["failed"], b["class"])),
        "failures": failures,
    }


def _coverage_threshold_pct(repo_root, default=60.0):
    """The gate's threshold, read from pom.xml (jacoco.core.minLineCoverage) so the dashboard cannot drift
    from `mvn verify`; CI may override it with -D, which a local read cannot see."""
    try:
        with open(os.path.join(repo_root, "pom.xml"), encoding="utf-8") as f:
            m = re.search(r"<jacoco\.core\.minLineCoverage>\s*([0-9.]+)\s*<", f.read())
        return round(float(m.group(1)) * 100, 2) if m else default
    except (OSError, ValueError):
        return default


def coverage_summary(repo_root):
    data = _read_json(os.path.join(repo_root, "target", "coverage-summary.json"))
    if data:
        return data

    # Fall back to parsing jacoco.xml directly (same scope/logic as
    # compute_coverage_summary.py) — that script only runs as part of the
    # coverage-gate CI job, so a purely local `mvn test -Psome-profile`
    # run may have jacoco.xml but never have produced the JSON.
    jacoco_path = os.path.join(repo_root, "target", "site", "jacoco", "jacoco.xml")
    if not os.path.exists(jacoco_path):
        return None
    try:
        root = ET.parse(jacoco_path).getroot()
    except ET.ParseError:
        return None
    covered = missed = 0
    for package in root.findall("package"):
        if not package.get("name", "").startswith("com/automation/core"):
            continue
        for counter in package.findall("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))
    total = covered + missed
    if total == 0:
        return None
    pct = round(100.0 * covered / total, 2)
    threshold = _coverage_threshold_pct(repo_root)
    return {"line_covered": covered, "line_missed": missed, "line_total": total,
            "line_pct": pct, "threshold_pct": threshold, "passed": pct >= threshold,
            "scope": "com.automation.core.*", "source": "jacoco.xml (local fallback)"}


def self_healing_summary(repo_root, detail_limit=100):
    """
    {'total', 'bySite', 'events': [{'site','original','healedTo','stage'}, ...]}
    `events` is capped at `detail_limit` (newest-looking entries first, per
    the order each site's report already lists them in) — this is a
    dashboard panel, not a full report; docs/AI_FEATURES.md and the
    Allure self-healing attachment are still the complete record.

    File discovery has to cover BOTH shapes SelfHealingReportWriter can
    produce (see its `self-healing.report.path` property, default in
    global.properties): every CI job passes an explicit
    `-Dself-healing.report.path=target/self-healing/<site>-healing-report.json`
    override (see github-ci.yml/.gitlab-ci.yml/Jenkinsfile — needed so
    parallel jobs sharing one target/ don't overwrite each other's
    report), but a plain LOCAL `mvn test` — this dashboard's main
    audience — has no such override and gets the bare, un-prefixed
    default filename instead. A glob requiring a "-" before
    "healing-report.json" only matches the CI-style prefixed form and
    silently finds nothing for the far more common local-run case, which
    is the bug this comment is here to stop someone re-introducing.
    """
    events = []
    by_site = {}
    for path in sorted(glob.glob(os.path.join(repo_root, "target", "self-healing", "*healing-report.json"))):
        filename = os.path.basename(path)
        site = "(local run)" if filename == "healing-report.json" else filename[: -len("-healing-report.json")]
        raw = _read_json(path)
        if not isinstance(raw, list):
            continue
        by_site[site] = by_site.get(site, 0) + len(raw)
        for item in raw:
            if not isinstance(item, dict):
                continue
            events.append({
                "site": site,
                "original": item.get("originalLocator") or item.get("original") or item.get("elementKey") or "",
                "healedTo": item.get("healedDescription") or item.get("healedTo") or "",
                # matchMethod is HealingEvent's real field name (see
                # SelfHealingEngine.heal()); "stage" kept as a fallback
                # only in case a future schema renames it.
                "stage": item.get("matchMethod") or item.get("stage") or "",
            })

    total = sum(by_site.values())
    summarized = _read_json(os.path.join(repo_root, "target", "self-healing-summary.json"))
    if summarized and isinstance(summarized, dict):
        total = summarized.get("total", total)
        by_site = summarized.get("bySite", by_site)

    if total == 0 and not by_site and not events:
        return None
    return {"total": total, "bySite": by_site, "events": events[:detail_limit]}


def flaky_tests(repo_root):
    """
    Passes target/flaky-tests.json through largely as-is (shape:
    {'flaky_count', 'flaky': [{'test','pass_count','fail_count',
    'skip_count','recent_statuses'}, ...]} — see
    .github/workflows/scripts/compute_flaky_trend.py). This file only
    exists after at least one gh-pages-history-producing CI run, so it's
    normal for a pure-local checkout to have none.
    """
    return _read_json(os.path.join(repo_root, "target", "flaky-tests.json"))


def tia_impact(repo_root):
    """{'generatedAt','mode','impacted_count','total_test_classes','changed_files_count'} — see ReportWriter.writeImpactSummaryJson."""
    return _read_json(os.path.join(repo_root, "target", "tia", "impact-summary.json"))


# ── per-testcase detail (failure messages, slowest tests, class durations) ─────────────────────────────
_MAX_XML_BYTES = 25 * 1024 * 1024      # a runaway report must not stall the dashboard
_MAX_FAILURE_DETAILS = 60
_MAX_SLOWEST = 10
_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _clean(text, limit):
    """Single-line-ish, control-character-free, length-capped text for the UI (which also HTML-escapes it)."""
    text = _CONTROL_CHARS.sub("", text or "").strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "\u2026"


def _seconds(value):
    try:
        v = float(value)
        return v if v >= 0 else 0.0
    except (TypeError, ValueError):
        return 0.0


def testcase_details(repo_root, with_cases=False):
    """
    Everything the class-level summary above cannot show, read from surefire's JUnit-style TEST-*.xml:

      {'duration_s': total seconds, 'class_time': {class: seconds},
       'slowest': [{'class','name','seconds'}, ...],
       'failure_details': [{'class','name','kind','message'}, ...]}

    Best effort: an unreadable, oversized or malformed file is skipped, and a repo with no reports returns
    the empty shape. Messages are length-capped and stripped of control characters (they are test output and
    can contain anything); the UI escapes them again when rendering.
    """
    empty = {"duration_s": 0.0, "class_time": {}, "slowest": [], "failure_details": []}
    folder = os.path.join(repo_root, "target", "surefire-reports")
    # Same file rules as _junit_utils.parse_surefire_dir (the parser the totals above come from): walk
    # sub-folders, and skip the aggregate TEST-TestSuite.xml / testng-results.xml, which repeat every testcase
    # already present in the per-class files and would double every duration and failure.
    skip = {"TEST-TestSuite.xml", "testng-results.xml"}
    cases = {}  # (class, name) -> (seconds, failure-dict or None); a failure always wins over a duplicate
    skipped = set()
    for path in sorted(glob.glob(os.path.join(folder, "**", "TEST-*.xml"), recursive=True)):
        if os.path.basename(path) in skip:
            continue
        try:
            if os.path.getsize(path) > _MAX_XML_BYTES:
                continue
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        if root.tag != "testsuite":
            continue
        suite_name = root.get("name", os.path.basename(path))
        for tc in root.findall("testcase"):
            cls = tc.get("classname") or suite_name
            name = tc.get("name") or "unknown"
            key = (cls, name)
            if key in cases and cases[key][1] is not None:
                continue
            secs = _seconds(tc.get("time"))
            bad, kind = tc.find("failure"), "failure"
            if bad is None:
                bad, kind = tc.find("error"), "error"
            failure = None
            if bad is not None and tc.find("skipped") is None:
                first_line = (bad.text or "").strip().splitlines()[:1]
                message = bad.get("message") or (first_line[0] if first_line else "")
                failure = {"class": _clean(cls, 200), "name": _clean(name, 200), "kind": kind,
                           "type": _clean(bad.get("type") or "", 120), "message": _clean(message, 400)}
            cases[key] = (secs, failure)
            if tc.find("skipped") is not None:
                skipped.add(key)
            else:
                skipped.discard(key)
    if not cases:
        return empty
    class_time = {}
    for (cls, _name), (secs, _f) in cases.items():
        class_time[cls] = round(class_time.get(cls, 0.0) + secs, 3)
    ordered = sorted(cases.items(), key=lambda kv: (-kv[1][0], kv[0]))
    out = {
        "duration_s": round(sum(v[0] for v in cases.values()), 2),
        "class_time": class_time,
        "slowest": [{"class": _clean(c, 200), "name": _clean(n, 200), "seconds": round(t, 3)}
                    for (c, n), (t, _f) in ordered[:_MAX_SLOWEST] if t > 0],
        "failure_details": [v[1] for _k, v in sorted(cases.items()) if v[1] is not None][:_MAX_FAILURE_DETAILS],
    }
    if with_cases:   # every test's outcome, for case_history only — callers pop it before anything reaches the browser
        out["cases"] = [[f"{_clean(c, 200)}.{_clean(n, 200)}".lstrip("."),
                         1 if v[1] is not None else (2 if (c, n) in skipped else 0), round(v[0], 3)]
                        for (c, n), v in sorted(cases.items())]
    return out


def full_snapshot(repo_root):
    surefire = surefire_summary(repo_root)
    details = testcase_details(repo_root, with_cases=True)
    cases = details.pop("cases", None)
    # The only writes this module makes: small history files in the dashboard's own data dir (not the repo).
    history = results_history.record(repo_root, surefire, details, cases)
    flaky = flaky_tests(repo_root)
    return {
        "surefire": surefire,
        # The trend only needs the numbers; the per-run failing-test names stay server-side (they feed `stability`).
        "history": [{k: v for k, v in e.items() if k not in ("fails", "slow", "msgs")} for e in history],
        "coverage": coverage_summary(repo_root),
        "self_healing": self_healing_summary(repo_root),
        "flaky": flaky,
        "stability": stability.rank(history, flaky),
        "tia": tia_impact(repo_root),
        "details": details,
    }
