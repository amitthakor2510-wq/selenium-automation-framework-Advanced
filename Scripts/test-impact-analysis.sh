#!/usr/bin/env bash
# Usage: ./Scripts/test-impact-analysis.sh --base <ref> [--head <ref>] [--run] [--browser <browser>]
# Examples:
#   ./Scripts/test-impact-analysis.sh --base origin/main            # just show what would run
#   ./Scripts/test-impact-analysis.sh --base origin/main --run      # actually run it
#   ./Scripts/test-impact-analysis.sh --base HEAD~5 --run --browser firefox
#
# Computes the set of test classes actually affected by everything changed
# between <base> and <head> (default head: the current working tree, i.e.
# "everything I've changed so far, committed or not") via
# com.automation.core.tia.TestImpactAnalyzer, then — with --run — executes
# ONLY those tests, one `mvn test` per site (this framework selects its site
# through a single -Dsite=... JVM system property and can't mix sites in one
# run; see ConfigReader's own javadoc on that constraint).
#
# Falls back to the full suite whenever TIA itself decides it can't safely
# narrow things down (a build/config file changed, a resource with no
# traceable owner, etc.) — see TEST_IMPACT_ANALYSIS.md for exactly which
# changes trigger that and why.

set -euo pipefail
cd "$(dirname "$0")/.."

BASE=""
HEAD=""
DO_RUN="false"
BROWSER="chrome"
HEADLESS="${HEADLESS:-true}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --head) HEAD="$2"; shift 2 ;;
    --run) DO_RUN="true"; shift ;;
    --browser) BROWSER="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$BASE" ]]; then
  echo "Usage: $0 --base <ref> [--head <ref>] [--run] [--browser <browser>]" >&2
  exit 1
fi

echo "======= Test Impact Analysis: compiling ======="
mvn -q -B compile test-compile

echo "======= Test Impact Analysis: computing impacted tests (base=$BASE head=${HEAD:-<working tree>}) ======="
EXEC_ARGS=(-q exec:java@tia -Ptia -Dtia.base="$BASE")
if [[ -n "$HEAD" ]]; then
  EXEC_ARGS+=(-Dtia.head="$HEAD")
fi
mvn -B "${EXEC_ARGS[@]}"

MODE_FILE="target/tia/mode.txt"
if [[ ! -f "$MODE_FILE" ]]; then
  echo "Expected $MODE_FILE to exist after the tia goal ran — something went wrong above." >&2
  exit 1
fi
MODE="$(cat "$MODE_FILE")"

echo ""
echo "======= Mode: $MODE ======="
cat target/tia/impact-report.md
echo ""

if [[ "$DO_RUN" != "true" ]]; then
  echo "(dry run — pass --run to actually execute these tests)"
  exit 0
fi

if [[ "$MODE" == "FULL" ]]; then
  echo "======= Running the FULL suite for every site (TIA fell back — see reasons above) ======="
  for suite in testng-suites/demoqa-regression.xml testng-suites/saucedemo-regression.xml; do
    site="$(basename "$suite" | cut -d- -f1)"
    echo "--- $site (full) ---"
    mvn -B test -Dsite="$site" -DsuiteXmlFile="$suite" -Dbrowser="$BROWSER" -Dheadless="$HEADLESS" \
      -Dhuman.pause.enabled=false -Dmaven.test.failure.ignore=true
  done
  exit 0
fi

echo "======= Running only impacted tests, per site ======="
ANY_SITE_RUN="false"
for suite_xml in target/tia/testng-impacted-*.xml; do
  [[ -e "$suite_xml" ]] || continue
  site="$(basename "$suite_xml" | sed -E 's/^testng-impacted-(.*)\.xml$/\1/')"
  if [[ "$site" == "other" ]]; then
    echo "--- skipping 'other' (non-TestNG / non-site-package impacted classes — see impact-report.md) ---"
    continue
  fi
  echo "--- $site (impacted only) ---"
  mvn -B test -Dsite="$site" -DsuiteXmlFile="$suite_xml" -Dbrowser="$BROWSER" -Dheadless="$HEADLESS" \
    -Dhuman.pause.enabled=false -Dmaven.test.failure.ignore=true
  ANY_SITE_RUN="true"
done

if [[ "$ANY_SITE_RUN" != "true" ]]; then
  echo "Nothing impacted — nothing to run."
fi
