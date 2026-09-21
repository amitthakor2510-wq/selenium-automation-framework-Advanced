#!/usr/bin/env python3
"""
Posts a one-message test-run summary to Slack or Microsoft Teams.

One script for all three CI systems this repo ships (GitHub Actions,
GitLab CI, Jenkins) — it reads whichever CI's own built-in environment
variables are present, so no pipeline needs to translate anything for it.

Configuration (all environment variables):

  CHAT_WEBHOOK_URL   REQUIRED to do anything. Unset/blank -> the script prints
                     "not configured" and exits 0, so wiring this into a
                     pipeline is safe before anyone has created a webhook.
                     Keep it in the CI system's secret store, never in a file.
  CHAT_PROVIDER      slack | teams | auto (default). auto picks Teams for
                     *.webhook.office.com / *.logic.azure.com /
                     *.powerplatform.com / *.api.powerplatform.com URLs and
                     Slack for everything else (which also covers
                     Slack-compatible endpoints such as Mattermost).
  NOTIFY_ON          failure (default) | always. "failure" stays silent on an
                     all-green run so the channel is worth reading.
  PIPELINE_RESULT    Optional overall CI verdict (success/failure/cancelled),
                     e.g. GitHub's needs.<job>.result. A non-success verdict
                     counts as a failure even if no test XML was produced
                     (compile error, crashed job) — a run with zero parsed
                     tests is never reported as "passed".
  SUREFIRE_DIR       Where TEST-*.xml live (default: target/surefire-reports).
  REPORT_URL         Optional link to the published Allure/Extent report.
  MAX_FAILURES_LISTED  Failed test names shown (default 10).

The script NEVER fails the pipeline: a webhook outage must not turn a green
build red. Delivery problems are printed and the exit code stays 0.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _junit_utils import parse_surefire_dir  # noqa: E402

TEAMS_HOST_MARKERS = (
    "webhook.office.com",
    "logic.azure.com",
    "powerplatform.com",
)


def detect_provider(url, requested="auto"):
    requested = (requested or "auto").strip().lower()
    if requested in ("slack", "teams"):
        return requested
    lowered = (url or "").lower()
    return "teams" if any(m in lowered for m in TEAMS_HOST_MARKERS) else "slack"


def ci_context(env=None):
    """Best-effort run metadata from whichever CI system is executing us."""
    e = os.environ if env is None else env
    if e.get("GITHUB_ACTIONS"):
        server = e.get("GITHUB_SERVER_URL", "https://github.com")
        repo = e.get("GITHUB_REPOSITORY", "")
        return {
            "ci": "GitHub Actions",
            "project": repo,
            "branch": e.get("GITHUB_HEAD_REF") or e.get("GITHUB_REF_NAME", ""),
            "commit": e.get("GITHUB_SHA", ""),
            "run_url": f"{server}/{repo}/actions/runs/{e.get('GITHUB_RUN_ID', '')}" if repo else "",
        }
    if e.get("GITLAB_CI"):
        return {
            "ci": "GitLab CI",
            "project": e.get("CI_PROJECT_PATH", ""),
            "branch": e.get("CI_COMMIT_REF_NAME", ""),
            "commit": e.get("CI_COMMIT_SHA", ""),
            "run_url": e.get("CI_PIPELINE_URL", ""),
        }
    if e.get("JENKINS_URL") or e.get("BUILD_URL"):
        return {
            "ci": "Jenkins",
            "project": e.get("JOB_NAME", ""),
            "branch": e.get("BRANCH_NAME") or e.get("GIT_BRANCH", ""),
            "commit": e.get("GIT_COMMIT", ""),
            "run_url": e.get("BUILD_URL", ""),
        }
    return {"ci": "CI", "project": "", "branch": "", "commit": "", "run_url": ""}


def should_notify(totals, pipeline_result, notify_on):
    """(send?, is_failure). Zero parsed tests is NOT success: a crashed job
    that never wrote a report must not produce a green 'all passed' message."""
    failed = totals["failed"] > 0
    verdict_bad = (pipeline_result or "").strip().lower() in ("failure", "failed", "cancelled", "canceled", "error")
    no_tests = totals["total"] == 0
    is_failure = failed or verdict_bad or no_tests
    if (notify_on or "failure").strip().lower() == "always":
        return True, is_failure
    return is_failure, is_failure


def build_summary(totals, failures, ctx, report_url, is_failure, pipeline_result, max_listed):
    if totals["total"] == 0:
        headline = "No test results were produced"
        detail = "The run finished without any parsable surefire reports (compile error or crashed job?)."
    elif totals["failed"] > 0:
        headline = f"{totals['failed']} of {totals['total']} tests failed"
        detail = f"{totals['passed']} passed, {totals['skipped']} skipped."
    elif is_failure:
        headline = "Pipeline finished with a failure"
        detail = f"All {totals['total']} parsed tests passed, but the pipeline verdict was '{pipeline_result}'."
    else:
        headline = f"All {totals['passed']} tests passed"
        detail = f"{totals['skipped']} skipped." if totals["skipped"] else "No skips."
    shown = failures[:max_listed]
    more = len(failures) - len(shown)
    return {
        "icon": "🔴" if is_failure else "✅",
        "headline": headline,
        "detail": detail,
        "failures": shown,
        "more": max(more, 0),
        "project": ctx["project"],
        "ci": ctx["ci"],
        "branch": ctx["branch"],
        "commit": (ctx["commit"] or "")[:8],
        "run_url": ctx["run_url"],
        "report_url": report_url,
    }


def slack_payload(s):
    title = f"{s['icon']} {s['headline']}"
    meta = " · ".join(x for x in (s["project"], s["ci"], s["branch"], s["commit"]) if x)
    lines = [f"*{title}*", s["detail"]]
    if meta:
        lines.append(f"_{meta}_")
    if s["failures"]:
        lines.append("*Failed:*\n" + "\n".join(f"• `{name}`" for name in s["failures"]))
        if s["more"]:
            lines.append(f"…and {s['more']} more")
    blocks = [{"type": "section", "text": {"type": "mrkdwn", "text": "\n".join(lines)}}]
    buttons = []
    if s["run_url"]:
        buttons.append({"type": "button", "text": {"type": "plain_text", "text": "Open run"}, "url": s["run_url"]})
    if s["report_url"]:
        buttons.append({"type": "button", "text": {"type": "plain_text", "text": "Test report"}, "url": s["report_url"]})
    if buttons:
        blocks.append({"type": "actions", "elements": buttons})
    return {"text": title, "blocks": blocks}


def teams_payload(s):
    body = [
        {"type": "TextBlock", "size": "Medium", "weight": "Bolder", "wrap": True,
         "text": f"{s['icon']} {s['headline']}"},
        {"type": "TextBlock", "wrap": True, "text": s["detail"]},
    ]
    facts = [{"title": t, "value": v} for t, v in
             (("Project", s["project"]), ("CI", s["ci"]), ("Branch", s["branch"]), ("Commit", s["commit"])) if v]
    if facts:
        body.append({"type": "FactSet", "facts": facts})
    if s["failures"]:
        body.append({"type": "TextBlock", "weight": "Bolder", "text": "Failed tests"})
        body.append({"type": "TextBlock", "wrap": True, "fontType": "Monospace",
                     "text": "\n".join(f"- {n}" for n in s["failures"])
                             + (f"\n- …and {s['more']} more" if s["more"] else "")})
    actions = []
    if s["run_url"]:
        actions.append({"type": "Action.OpenUrl", "title": "Open run", "url": s["run_url"]})
    if s["report_url"]:
        actions.append({"type": "Action.OpenUrl", "title": "Test report", "url": s["report_url"]})
    card = {"$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
            "type": "AdaptiveCard", "version": "1.4", "body": body}
    if actions:
        card["actions"] = actions
    return {"type": "message",
            "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive",
                             "contentUrl": None, "content": card}]}


def post_json(url, payload, attempts=3, timeout=15):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    last = None
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if 200 <= resp.status < 300:
                    return True
                last = f"HTTP {resp.status}"
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if 400 <= e.code < 500 and e.code != 429:
                break  # a bad/revoked webhook URL won't fix itself on retry
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = str(e)
        if attempt < attempts:
            time.sleep(min(2 ** attempt, 5))
    print(f"notify_chat: delivery failed after retries ({last}) — not failing the pipeline.")
    return False


def main(env=None):
    e = os.environ if env is None else env
    url = (e.get("CHAT_WEBHOOK_URL") or "").strip()
    if not url:
        print("notify_chat: CHAT_WEBHOOK_URL not set — skipping (notifications not configured).")
        return 0

    results, totals = parse_surefire_dir(e.get("SUREFIRE_DIR", "target/surefire-reports"))
    pipeline_result = e.get("PIPELINE_RESULT", "")
    send, is_failure = should_notify(totals, pipeline_result, e.get("NOTIFY_ON", "failure"))
    if not send:
        print(f"notify_chat: run is green ({totals['passed']}/{totals['total']} passed) and NOTIFY_ON=failure — nothing to send.")
        return 0

    failures = sorted(k for k, v in results.items() if v == "fail")
    try:
        max_listed = int(e.get("MAX_FAILURES_LISTED", "10"))
    except ValueError:
        max_listed = 10
    ctx = ci_context(e)
    summary = build_summary(totals, failures, ctx, e.get("REPORT_URL", ""), is_failure, pipeline_result, max_listed)
    provider = detect_provider(url, e.get("CHAT_PROVIDER", "auto"))
    payload = teams_payload(summary) if provider == "teams" else slack_payload(summary)
    ok = post_json(url, payload)
    print(f"notify_chat: {provider} notification {'sent' if ok else 'NOT delivered'}.")
    return 0  # never fail the pipeline over a notification


if __name__ == "__main__":
    sys.exit(main())
