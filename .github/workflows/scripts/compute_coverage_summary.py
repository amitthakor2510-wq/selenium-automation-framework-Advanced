"""
Writes target/coverage-summary.json — a machine-readable line-coverage
summary scoped to com.automation.core.* (the same bundle/package scope
jacoco:check@jacoco-check in pom.xml enforces the 50% gate against), read
back by generate_landing_page.py for the unified dashboard.

jacoco:report's own target/site/jacoco/jacoco.xml already contains this
data, but only as per-package <counter> elements inside a much larger
report (every package in the project, not just core/); the gate itself
only exists as jacoco:check's pass/fail exit code, with no JSON of its
own. This script re-derives the same core/-scoped percentage jacoco:check
computes, by summing each <package name="com/automation/core...">
element's own LINE counter — so the dashboard shows the identical number
the gate is actually enforcing, not a whole-project figure that would be
misleadingly different (site/mobile page objects are exercised by the
suites themselves and deliberately excluded from the gate — see
pom.xml's jacoco-check comment).

Reads:  target/site/jacoco/jacoco.xml   (from `mvn jacoco:report@jacoco-report`)
Writes: target/coverage-summary.json
"""
import json
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

JACOCO_XML_PATH = "target/site/jacoco/jacoco.xml"
OUTPUT_PATH = "target/coverage-summary.json"
CORE_PACKAGE_PREFIX = "com/automation/core"
THRESHOLD_PCT = 50.0


def main():
    if not os.path.exists(JACOCO_XML_PATH):
        print(f"::warning::{JACOCO_XML_PATH} not found — skipping coverage summary")
        return

    # jacoco.xml declares a DOCTYPE referencing an external DTD
    # (report.dtd) — confirmed this doesn't trigger any network fetch or
    # parse failure under stdlib ElementTree/expat, so a plain parse is
    # all that's needed here.
    try:
        tree = ET.parse(JACOCO_XML_PATH)
    except ET.ParseError as e:
        print(f"::warning::Could not parse {JACOCO_XML_PATH}: {e}")
        return
    root = tree.getroot()

    covered = 0
    missed = 0
    matched_packages = 0
    for package in root.findall("package"):
        name = package.get("name", "")
        if not name.startswith(CORE_PACKAGE_PREFIX):
            continue
        matched_packages += 1
        for counter in package.findall("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))

    if matched_packages == 0:
        print(f"::warning::No packages under {CORE_PACKAGE_PREFIX} found in {JACOCO_XML_PATH}")
        return

    total = covered + missed
    pct = round(100.0 * covered / total, 2) if total > 0 else 0.0

    summary = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "scope": CORE_PACKAGE_PREFIX.replace("/", ".") + ".*",
        "line_covered": covered,
        "line_missed": missed,
        "line_total": total,
        "line_pct": pct,
        "threshold_pct": THRESHOLD_PCT,
        "passed": pct >= THRESHOLD_PCT,
    }

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"Coverage ({summary['scope']}): {pct}% ({covered}/{total} lines) "
          f"— {'PASS' if summary['passed'] else 'FAIL'} against {THRESHOLD_PCT}% threshold")


if __name__ == "__main__":
    sys.exit(main())
