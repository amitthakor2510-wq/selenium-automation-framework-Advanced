#!/usr/bin/env python3
"""
Coverage-gate headroom check — answers "is the 50% com.automation.core.*
threshold actually achievable with today's tests, and by how much?" without
waiting for a CI run to go red.

Reads the same target/site/jacoco/jacoco.xml the gate's jacoco:report step
produces, sums LINE counters for com/automation/core* packages exactly like
.github/workflows/scripts/compute_coverage_summary.py (and therefore like
jacoco:check@jacoco-check), prints a per-package table sorted by missed
lines, and reports headroom against the threshold in pom.xml
(<jacoco.core.minLineCoverage>).

Typical local use (merges whatever .exec files you have, then reports):

    mvn -B test -Dsite=demoqa                 # or any suite(s) you care about
    mkdir -p target/jacoco-raw && cp target/jacoco.exec target/jacoco-raw/demoqa.exec
    # ...repeat for other sites/mobile/api, giving each a distinct name...
    mvn -B jacoco:merge@jacoco-merge jacoco:report@jacoco-report
    python3 Scripts/coverage-headroom.py

Exit code: 0 = at/above threshold, 1 = below, 2 = no usable report.
Pass --threshold 45 to test a hypothetical value without touching pom.xml.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

JACOCO_XML = "target/site/jacoco/jacoco.xml"
POM = "pom.xml"
CORE_PREFIX = "com/automation/core"
SAFETY_MARGIN_PCT = 5.0  # suggested threshold sits this far under the measured value


def pom_threshold_pct():
    try:
        text = open(POM, encoding="utf-8").read()
    except OSError:
        return 50.0
    m = re.search(r"<jacoco\.core\.minLineCoverage>\s*([0-9.]+)\s*</jacoco\.core\.minLineCoverage>", text)
    return round(float(m.group(1)) * 100.0, 2) if m else 50.0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--threshold", type=float, help="percent to test against (default: from pom.xml)")
    ap.add_argument("--xml", default=JACOCO_XML)
    ap.add_argument("--top", type=int, default=15, help="rows of the per-package table (default 15)")
    args = ap.parse_args()

    if not os.path.exists(args.xml):
        print(f"No {args.xml} — run `mvn jacoco:report@jacoco-report` after a test run/merge first.")
        return 2
    try:
        root = ET.parse(args.xml).getroot()
    except ET.ParseError as e:
        print(f"Could not parse {args.xml}: {e}")
        return 2

    rows = []
    for pkg in root.findall("package"):
        name = pkg.get("name", "")
        if not name.startswith(CORE_PREFIX):
            continue
        for c in pkg.findall("counter"):
            if c.get("type") == "LINE":
                rows.append((name.replace("/", "."), int(c.get("covered", 0)), int(c.get("missed", 0))))
    if not rows:
        print(f"No packages under {CORE_PREFIX} in {args.xml}.")
        return 2

    covered = sum(r[1] for r in rows)
    missed = sum(r[2] for r in rows)
    total = covered + missed
    pct = 100.0 * covered / total if total else 0.0
    threshold = args.threshold if args.threshold is not None else pom_threshold_pct()

    print(f"{'package':<52}{'covered':>9}{'missed':>8}{'line %':>8}")
    print("-" * 77)
    for name, cov, mis in sorted(rows, key=lambda r: -r[2])[: args.top]:
        t = cov + mis
        print(f"{name:<52}{cov:>9}{mis:>8}{(100.0 * cov / t if t else 0.0):>7.1f}%")
    if len(rows) > args.top:
        print(f"... {len(rows) - args.top} more package(s)")
    print("-" * 77)
    print(f"{'com.automation.core.* (gate scope)':<52}{covered:>9}{missed:>8}{pct:>7.1f}%")
    print()

    headroom = pct - threshold
    needed = max(0, int(-(-threshold * total // 100)) - covered)  # ceil
    if headroom >= 0:
        print(f"PASS: {pct:.2f}% vs {threshold:g}% threshold ({headroom:+.2f} pts headroom).")
        if headroom < 2:
            print("      Thin margin — a small refactor that adds uncovered lines could flip the gate red.")
        return 0

    suggested = max(0.0, (int(pct - SAFETY_MARGIN_PCT) // 5) * 5)
    print(f"FAIL: {pct:.2f}% vs {threshold:g}% threshold ({headroom:+.2f} pts).")
    print(f"      Need {needed} more covered line(s) to pass, or lower the threshold.")
    print(f"      If you choose to lower it, set <jacoco.core.minLineCoverage>{suggested / 100:.2f}</jacoco.core.minLineCoverage>")
    print("      in pom.xml (the dashboard tile and CI summary read it from there), then raise it")
    print("      back as tests are added. The packages with the most missed lines above are the")
    print("      cheapest place to add coverage.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
