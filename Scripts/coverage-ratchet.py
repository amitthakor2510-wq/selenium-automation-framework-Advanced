#!/usr/bin/env python3
"""
Coverage ratchet — fails when merged line coverage of com.automation.core.* falls more than
`core.max.drop.pct` points (default 1.0) below the last recorded baseline in
coverage-baseline.properties.

Why: a fixed threshold (pom.xml <jacoco.core.minLineCoverage>, 60%) goes stale as the code grows —
coverage can slide from 85% to 61% without the gate noticing. The ratchet keeps the bar at "what we
actually have", while the pom value stays as the absolute floor.

Reads  : target/coverage-summary.json (written by .github/workflows/scripts/compute_coverage_summary.py)
         or, if that is missing, target/site/jacoco/jacoco.xml (same maths as the summary script).
Usage  : python3 Scripts/coverage-ratchet.py            # check
         python3 Scripts/coverage-ratchet.py --update   # raise the baseline if coverage went up
         python3 Scripts/coverage-ratchet.py --baseline path/to/file.properties
Exit   : 0 = OK (or no baseline recorded yet / no coverage data), 1 = dropped too far, 2 = bad input.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

SUMMARY_JSON = "target/coverage-summary.json"
JACOCO_XML = "target/site/jacoco/jacoco.xml"
CORE_PREFIX = "com/automation/core"
DEFAULT_BASELINE = "coverage-baseline.properties"
DEFAULT_MAX_DROP = 1.0


def read_props(path):
    props = {}
    if not os.path.exists(path):
        return props
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def write_baseline_value(path, pct):
    """Rewrite only the core.line.pct line so comments and other keys stay untouched."""
    lines = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    replaced = False
    for i, line in enumerate(lines):
        if line.strip().startswith("core.line.pct"):
            lines[i] = f"core.line.pct={pct:.2f}"
            replaced = True
    if not replaced:
        lines.append(f"core.line.pct={pct:.2f}")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def measured_pct():
    if os.path.exists(SUMMARY_JSON):
        try:
            with open(SUMMARY_JSON, encoding="utf-8") as fh:
                return float(json.load(fh)["line_pct"])
        except (OSError, ValueError, KeyError):
            pass
    if not os.path.exists(JACOCO_XML):
        return None
    try:
        root = ET.parse(JACOCO_XML).getroot()
    except ET.ParseError:
        return None
    covered = missed = 0
    for pkg in root.findall("package"):
        if not pkg.get("name", "").startswith(CORE_PREFIX):
            continue
        for counter in pkg.findall("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))
    total = covered + missed
    return round(100.0 * covered / total, 2) if total else None


def parse_float(text, default=None):
    try:
        return float(text)
    except (TypeError, ValueError):
        return default


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--baseline", default=DEFAULT_BASELINE)
    ap.add_argument("--update", action="store_true", help="raise the baseline to the measured value if it is higher")
    args = ap.parse_args(argv)

    pct = measured_pct()
    if pct is None:
        print("[coverage-ratchet] No coverage data found - nothing to compare (run jacoco:report first).")
        return 0

    props = read_props(args.baseline)
    baseline = parse_float(props.get("core.line.pct"))
    max_drop = parse_float(props.get("core.max.drop.pct"), DEFAULT_MAX_DROP)

    if baseline is None:
        print(f"[coverage-ratchet] Measured {pct:.2f}% - no baseline recorded yet, so nothing to enforce.")
        print(f"[coverage-ratchet] Record it: python3 Scripts/coverage-ratchet.py --update  (then commit {args.baseline})")
        if args.update:
            write_baseline_value(args.baseline, pct)
            print(f"[coverage-ratchet] Baseline set to {pct:.2f}%.")
        return 0

    floor = baseline - max_drop
    print(f"[coverage-ratchet] Measured {pct:.2f}% | baseline {baseline:.2f}% | allowed minimum {floor:.2f}%")

    if pct < floor:
        print(f"[coverage-ratchet] FAIL: coverage dropped {baseline - pct:.2f} points below the baseline "
              f"(allowed drop: {max_drop:.2f}). Add tests for the new/changed code in com.automation.core.*.")
        return 1

    if pct > baseline:
        if args.update:
            write_baseline_value(args.baseline, pct)
            print(f"[coverage-ratchet] Baseline raised {baseline:.2f}% -> {pct:.2f}%. Commit {args.baseline}.")
        else:
            print(f"[coverage-ratchet] Coverage is {pct - baseline:.2f} points above the baseline - "
                  "run with --update and commit to lock in the gain.")
    print("[coverage-ratchet] OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
