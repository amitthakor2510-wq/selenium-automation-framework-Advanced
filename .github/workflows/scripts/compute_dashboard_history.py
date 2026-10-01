"""
Appends this run's headline test-health numbers — coverage %, self-healing
count, flaky-test count, and (PR runs only) TIA-impacted % — to a small
rolling history file on gh-pages, the same pattern compute_flaky_trend.py
already established for test-history.json. Without this, the landing page
can only ever show a snapshot of the current run; this is what turns
"coverage is 61% right now" into "coverage over the last 20 runs".

Must run AFTER "Assemble combined GitHub Pages publish directory" and
AFTER every compute_*.py script whose output it reads, for the same
reason compute_flaky_trend.py documents on its own step in
github-ci.yml: the Assemble step's rsync from the old gh-pages checkout
would otherwise silently overwrite this run's freshly-written history
with yesterday's stale copy.

Reads:  gh-pages/history/dashboard-history.json  (previous runs, if any)
        target/coverage-summary.json
        target/self-healing-summary.json
        target/flaky-tests.json
        target/tia/impact-summary.json            (PR runs only — may not exist)
Writes: target/gh-pages-publish/history/dashboard-history.json  (updated, capped history)
"""
import json
import os
from datetime import datetime, timezone

PREVIOUS_HISTORY_PATH = "gh-pages/history/dashboard-history.json"
HISTORY_OUTPUT_PATH = "target/gh-pages-publish/history/dashboard-history.json"

COVERAGE_SUMMARY_PATH = "target/coverage-summary.json"
SELF_HEALING_SUMMARY_PATH = "target/self-healing-summary.json"
FLAKY_TESTS_PATH = "target/flaky-tests.json"
TIA_SUMMARY_PATH = "target/tia/impact-summary.json"

# Same rationale/window as compute_flaky_trend.py's HISTORY_WINDOW — short
# on purpose, recent-signal over long-term archive.
HISTORY_WINDOW = 20


def load_json(path):
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"::warning::Could not read {path}: {e}")
        return None


def load_previous_history():
    if not os.path.exists(PREVIOUS_HISTORY_PATH):
        return []
    data = load_json(PREVIOUS_HISTORY_PATH)
    return data if isinstance(data, list) else []


def main():
    coverage = load_json(COVERAGE_SUMMARY_PATH)
    self_healing = load_json(SELF_HEALING_SUMMARY_PATH)
    flaky = load_json(FLAKY_TESTS_PATH)
    tia = load_json(TIA_SUMMARY_PATH)

    entry = {
        "run_id": os.environ.get("GITHUB_RUN_ID", "unknown"),
        "event": os.environ.get("GITHUB_EVENT_NAME", "unknown"),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "coverage_pct": coverage["line_pct"] if coverage else None,
        "self_healing_count": self_healing["total"] if self_healing else 0,
        "flaky_count": flaky["flaky_count"] if flaky else 0,
        # TIA only runs on pull_request events (see test-impact-analysis job's
        # `if:` in github-ci.yml) — None on every push/schedule run is expected,
        # not missing data.
        "tia_impacted_pct": (
            round(100.0 * tia["impacted_count"] / tia["total_test_classes"], 1)
            if tia and tia.get("mode") == "IMPACTED" and tia.get("total_test_classes")
            else None
        ),
    }

    history = load_previous_history()
    history.append(entry)
    history = history[-HISTORY_WINDOW:]

    os.makedirs(os.path.dirname(HISTORY_OUTPUT_PATH), exist_ok=True)
    with open(HISTORY_OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(history, f, indent=2)

    print(f"Dashboard history now covers {len(history)} run(s)")


if __name__ == "__main__":
    main()
