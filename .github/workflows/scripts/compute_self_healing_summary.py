"""
Aggregates every target/self-healing/<site>-healing-report.json produced
this run (each written by SelfHealingReportWriter, one per site/suite —
see the -Dself-healing.report.path override added to each mvn test
invocation in github-ci.yml, which is what keeps these filenames from
colliding once every job's artifacts are merged into one target/ dir) into
a single target/self-healing-summary.json, and exposes the total count as
a step output so the job summary step doesn't need to re-parse anything.

SelfHealingReportWriter only writes a file at all when at least one
locator actually healed during that run (see its `if events.isEmpty()`
guard), so a clean run with zero drift simply has no matching files here —
that's the expected, common case, not an error.
"""
import glob
import json
import os

SELF_HEALING_DIR = "target/self-healing"
OUTPUT_PATH = "target/self-healing-summary.json"


def main():
    by_site = {}
    all_events = []

    for path in sorted(glob.glob(os.path.join(SELF_HEALING_DIR, "*-healing-report.json"))):
        site = os.path.basename(path)[: -len("-healing-report.json")]
        try:
            with open(path, encoding="utf-8") as f:
                events = json.load(f)
        except (json.JSONDecodeError, OSError) as e:
            print(f"::warning::Could not read {path}: {e}")
            continue
        if not isinstance(events, list):
            continue
        for event in events:
            event = dict(event)
            event["site"] = site
            all_events.append(event)
        by_site[site] = events

    summary = {
        "total": len(all_events),
        "bySite": {site: len(events) for site, events in by_site.items()},
        "events": all_events,
    }

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"Self-healing events this run: {summary['total']} across {len(by_site)} site(s)")

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"healing_count={summary['total']}\n")


if __name__ == "__main__":
    main()
