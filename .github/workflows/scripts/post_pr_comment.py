"""
Posts (or, on re-runs of the same PR, updates in place) a single comment
on the pull request summarizing this run's test results, so a reviewer
sees pass/fail counts and a link to the full reports without leaving the
PR page.

Uses the `gh` CLI rather than actions/github-script — it's already
preinstalled and authenticated on GitHub-hosted runners via the GH_TOKEN
env var set on this step, and shells out just as easily from a Python
script as from JS, without needing a separate node-based action.

Requires (from the calling workflow step):
  env:
    GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    PR_NUMBER: ${{ github.event.pull_request.number }}
Also reads GITHUB_REPOSITORY (owner/repo) and GITHUB_RUN_ID, both of which
GitHub Actions sets automatically on every job — no explicit env: needed
for those two.
"""
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(__file__))
from _junit_utils import pages_base_url, parse_surefire_dir  # noqa: E402

SUREFIRE_DIR = "target/surefire-reports"
# Marker identifying "our" comment so re-runs edit it in place instead of
# stacking a fresh comment (and fresh notification) on every push to the
# same PR.
MARKER = "<!-- automation-framework-test-summary -->"


def find_existing_comment(repo, pr_number):
    result = subprocess.run(
        ["gh", "api", f"repos/{repo}/issues/{pr_number}/comments", "--paginate",
         "--jq", f'[.[] | select(.body | contains("{MARKER}"))][0].id // empty'],
        capture_output=True, text=True, check=False,
    )
    comment_id = result.stdout.strip()
    return comment_id or None


def build_body(totals, base_url, run_id):
    total, passed, failed, skipped = (
        totals["total"], totals["passed"], totals["failed"], totals["skipped"],
    )
    if total == 0:
        status_line = "⚠️ No test results were found for this run."
    elif failed > 0:
        status_line = f"❌ **{failed} of {total} tests failed.**"
    else:
        status_line = f"✅ **All {total} tests passed.**"

    lines = [
        MARKER,
        "## 🧪 Automation Test Results",
        "",
        status_line,
        "",
        "| Passed | Failed | Skipped | Total |",
        "|---|---|---|---|",
        f"| {passed} | {failed} | {skipped} | {total} |",
        "",
        f"[Full reports (Allure + Extent)]({base_url}/) &middot; "
        f"[Allure]({base_url}/allure-report/) &middot; "
        f"[Workflow run](https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{run_id})",
    ]
    return "\n".join(lines)


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    owner, _, repo_name = repo.partition("/")
    pr_number = os.environ.get("PR_NUMBER")
    run_id = os.environ.get("GITHUB_RUN_ID", "unknown")

    if not pr_number:
        print("PR_NUMBER not set — not a pull_request event, skipping comment.")
        return

    _, totals = parse_surefire_dir(SUREFIRE_DIR)
    base_url = pages_base_url(owner, repo_name)
    body = build_body(totals, base_url, run_id)

    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
        f.write(body)
        body_path = f.name

    existing_id = find_existing_comment(repo, pr_number)
    if existing_id:
        subprocess.run(
            ["gh", "api", "-X", "PATCH", f"repos/{repo}/issues/comments/{existing_id}",
             "-F", f"body=@{body_path}"],
            check=True,
        )
        print(f"Updated existing PR comment {existing_id}")
    else:
        subprocess.run(
            ["gh", "api", "-X", "POST", f"repos/{repo}/issues/{pr_number}/comments",
             "-F", f"body=@{body_path}"],
            check=True,
        )
        print("Posted new PR comment")


if __name__ == "__main__":
    main()
