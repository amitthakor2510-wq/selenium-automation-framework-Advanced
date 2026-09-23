"""
Read-only view over whatever test-result artifacts already exist under
target/ from the most recent local `mvn test` run(s). Never runs Maven,
never writes anything — the config-editing side of the dashboard
(config_files.py) is the only part with write access.

Every file here is optional: a fresh checkout with no run yet returns
empty/zero results rather than erroring, so the dashboard is useful
before the first test run too (just shows "no data yet").
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

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
    return {"line_covered": covered, "line_missed": missed, "line_total": total,
            "line_pct": pct, "threshold_pct": 50.0, "passed": pct >= 50.0,
            "scope": "com.automation.core.*", "source": "jacoco.xml (local fallback)"}


def self_healing_summary(repo_root, detail_limit=100):
    """
    {'total', 'bySite', 'events': [{'site','original','healedTo','stage'}, ...]}
    `events` is capped at `detail_limit` (newest-looking entries first, per
    the order each site's report already lists them in) — this is a
    dashboard panel, not a full report; docs/AI_FEATURES.md and the
    Allure self-healing attachment are still the complete record.
    """
    events = []
    by_site = {}
    for path in sorted(glob.glob(os.path.join(repo_root, "target", "self-healing", "*-healing-report.json"))):
        site = os.path.basename(path)[: -len("-healing-report.json")]
        raw = _read_json(path)
        if not isinstance(raw, list):
            continue
        by_site[site] = len(raw)
        for item in raw:
            if not isinstance(item, dict):
                continue
            events.append({
                "site": site,
                "original": item.get("originalLocator") or item.get("original") or item.get("elementKey") or "",
                "healedTo": item.get("healedDescription") or item.get("healedTo") or "",
                "stage": item.get("stage") or "",
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


def full_snapshot(repo_root):
    return {
        "surefire": surefire_summary(repo_root),
        "coverage": coverage_summary(repo_root),
        "self_healing": self_healing_summary(repo_root),
        "flaky": flaky_tests(repo_root),
        "tia": tia_impact(repo_root),
    }
