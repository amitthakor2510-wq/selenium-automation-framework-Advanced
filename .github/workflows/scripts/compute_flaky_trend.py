"""
Allure's own trend graph only tracks aggregate pass/fail counts per run —
it can't tell you WHICH test is the one flip-flopping. This script fills
that gap: it appends this run's per-test results to a small rolling
history file that lives on the gh-pages branch (so it persists across
runs, the same way Allure's own history/ dir already does — see the
"Load Previous Allure History" step earlier in this job for the existing
precedent), then flags any test whose status disagrees with itself across
the retained window.

Reads:  gh-pages/history/test-history.json   (previous runs, if any)
        target/surefire-reports/**           (this run's results)
Writes: target/gh-pages-publish/history/test-history.json  (updated, capped history)
        target/flaky-tests.json                             (this run's flaky-test list)
"""
import json
import os
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(__file__))
from _junit_utils import parse_surefire_dir  # noqa: E402

SUREFIRE_DIR = "target/surefire-reports"
PREVIOUS_HISTORY_PATH = "gh-pages/history/test-history.json"
HISTORY_OUTPUT_PATH = "target/gh-pages-publish/history/test-history.json"
FLAKY_OUTPUT_PATH = "target/flaky-tests.json"

# How many recent runs to retain. Kept short deliberately: this file is
# read back and re-parsed on every single run, and a long window mostly
# just captures the framework's early "figuring out the site" churn
# rather than useful signal about tests that are flaky *right now*.
HISTORY_WINDOW = 20


def load_previous_history():
    if not os.path.exists(PREVIOUS_HISTORY_PATH):
        return []
    try:
        with open(PREVIOUS_HISTORY_PATH, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError) as e:
        print(f"::warning::Could not read previous history at {PREVIOUS_HISTORY_PATH}: {e}")
        return []


def main():
    current_results, totals = parse_surefire_dir(SUREFIRE_DIR)

    history = load_previous_history()
    history.append({
        "run_id": os.environ.get("GITHUB_RUN_ID", "unknown"),
        "event": os.environ.get("GITHUB_EVENT_NAME", "unknown"),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "totals": totals,
        "tests": current_results,
    })
    history = history[-HISTORY_WINDOW:]

    os.makedirs(os.path.dirname(HISTORY_OUTPUT_PATH), exist_ok=True)
    with open(HISTORY_OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(history, f, indent=2)

    # A test is "flaky" here if, within the retained window, it shows up
    # with BOTH a pass and a fail at some point — not just failing once.
    # A test that has only ever failed is a real (probably legitimate)
    # failure to fix, not flakiness; the interesting signal is
    # inconsistency for what should be the same code path.
    per_test_statuses = {}
    for run in history:
        for test_key, status in run.get("tests", {}).items():
            per_test_statuses.setdefault(test_key, []).append(status)

    flaky = []
    for test_key, statuses in per_test_statuses.items():
        has_pass = "pass" in statuses
        has_fail = "fail" in statuses
        if has_pass and has_fail:
            flaky.append({
                "test": test_key,
                "pass_count": statuses.count("pass"),
                "fail_count": statuses.count("fail"),
                "skip_count": statuses.count("skip"),
                "recent_statuses": statuses[-10:],
            })
    flaky.sort(key=lambda t: t["fail_count"], reverse=True)

    flaky_report = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "window_runs": len(history),
        "flaky_count": len(flaky),
        "flaky": flaky,
    }
    with open(FLAKY_OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(flaky_report, f, indent=2)

    print(f"History now covers {len(history)} run(s); {len(flaky)} flaky test(s) detected this run")

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"flaky_count={len(flaky)}\n")


if __name__ == "__main__":
    main()
