#!/usr/bin/env bash
# Usage: ./Scripts/build-coverage-map.sh <site> [suiteXmlFile]
# Example: ./Scripts/build-coverage-map.sh demoqa
#          ./Scripts/build-coverage-map.sh saucedemo testng-suites/saucedemo-smoke.xml
#
# Builds (or refreshes) target/tia/coverage-map.txt — the "which test observed
# executing which class at runtime" data com.automation.core.tia.TestImpactAnalyzer
# uses as a second signal alongside its static bytecode dependency graph, closing
# the gap on reflection/dynamic-dispatch cases the bytecode graph can't see. See
# TEST_IMPACT_ANALYSIS.md → "Coverage-based fallback" for the full design.
#
# Two things make this different from an ordinary `mvn test` run, both required
# for the resulting map to be trustworthy (see JacocoPerTestCoverageListener's
# javadoc for why):
#   1. -Djacoco.jmx=true            — registers the JMX MBean the capture listener
#                                      resets/dumps around each test class.
#   2. -Dcoverage.map.enabled=true  — AlterSuiteForCoverageMapListener picks this up
#                                      and forces the suite to parallel="none" (a
#                                      shared, process-wide JaCoCo accumulator can't
#                                      be trusted to attribute coverage correctly to
#                                      one test class if another is running at the
#                                      same time on a different thread).
#
# Consequence: this is meaningfully SLOWER than the normal parallel CI run — that's
# expected and fine, since this is meant to run occasionally (nightly / on demand),
# not on every PR. See the coverage-map job in github-ci.yml.

set -euo pipefail
cd "$(dirname "$0")/.."

SITE="${1:-}"
SUITE="${2:-testng-suites/${SITE}-regression.xml}"

if [[ -z "$SITE" ]]; then
  echo "Usage: $0 <site> [suiteXmlFile]" >&2
  exit 1
fi
if [[ ! -f "$SUITE" ]]; then
  echo "Suite file not found: $SUITE" >&2
  exit 1
fi

echo "======= Coverage map: capturing per-test-class coverage for site=$SITE (serial — this is slower than normal, by design) ======="
rm -rf target/jacoco-per-test
mvn -B test \
  -Dsite="$SITE" \
  -DsuiteXmlFile="$SUITE" \
  -Dbrowser="${BROWSER:-chrome}" \
  -Dheadless="${HEADLESS:-true}" \
  -Dhuman.pause.enabled=false \
  -Djacoco.jmx=true \
  -Dcoverage.map.enabled=true \
  -Dmaven.test.failure.ignore=true

if [[ -f target/jacoco-per-test/UNRELIABLE.marker ]]; then
  echo "======= Capture marked UNRELIABLE — see reason below. No map will be built. =======" >&2
  cat target/jacoco-per-test/UNRELIABLE.marker >&2
  exit 1
fi

echo "======= Coverage map: building target/tia/coverage-map.txt ======="
mvn -B -q exec:java@coverage-map -Pcoverage-map

echo "======= Done ======="
if [[ -f target/tia/coverage-map.txt ]]; then
  wc -l < target/tia/coverage-map.txt | xargs -I{} echo "{} test-to-class observations written to target/tia/coverage-map.txt"
else
  echo "target/tia/coverage-map.txt was not created — check the output above."
  exit 1
fi
