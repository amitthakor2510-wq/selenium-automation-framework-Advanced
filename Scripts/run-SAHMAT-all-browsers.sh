#!/usr/bin/env bash
# Usage:
#   Scripts/run-SAHMAT-all-browsers.sh [smoke|regression] [extra mvn args...]
#
# Examples:
#   Scripts/run-SAHMAT-all-browsers.sh                       # regression, headless
#   Scripts/run-SAHMAT-all-browsers.sh smoke
#   Scripts/run-SAHMAT-all-browsers.sh regression -Dheadless=false
#   HEADLESS=false Scripts/run-SAHMAT-all-browsers.sh smoke
#
# Runs the SAHMAT suite against chrome, then firefox, then edge, ONE AT A
# TIME on this machine — not in parallel like the CI matrix/Jenkins
# ALL_BROWSERS branches do, since a laptop/workstation doesn't have the
# same headroom for 3 concurrent browser sessions plus everything else
# running on it. Each browser gets its own scoped output directories (same
# pattern the Jenkinsfile/github-ci.yml/.gitlab-ci.yml already use per
# site+browser combo — see pom.xml's <allure.results.directory> and
# <self-healing.report.path> property comments for why an unscoped path
# is unsafe) so one browser's run never overwrites another's results, then
# merges all three into one combined Allure report at the end.
#
# Safari is deliberately NOT included in this loop: it requires macOS +
# `sudo safaridriver --enable` and only ever allows one WebDriver session
# on the whole machine (see testng-suites/SAHMAT-safari-*.xml) — run it
# separately if needed:
#   mvn test -Dsite=SAHMAT -DsuiteXmlFile=testng-suites/SAHMAT-safari-regression.xml -Dbrowser=safari

set -uo pipefail
cd "$(dirname "$0")/.."

SUITE_TYPE="${1:-regression}"
if [[ "$SUITE_TYPE" != "smoke" && "$SUITE_TYPE" != "regression" ]]; then
  echo "Usage: $0 [smoke|regression] [extra mvn args...]"
  exit 1
fi
shift || true
EXTRA_ARGS="$*"

SUITE_FILE="testng-suites/SAHMAT-${SUITE_TYPE}.xml"
if [[ ! -f "$SUITE_FILE" ]]; then
  echo "[✗] $SUITE_FILE not found."
  exit 1
fi

HEADLESS="${HEADLESS:-true}"
RETRY_COUNT="${RETRY_COUNT:-0}"
BROWSERS=(chrome firefox edge)

COMBINED_DIR="target/allure-results/SAHMAT-all-browsers-${SUITE_TYPE}"
rm -rf "$COMBINED_DIR"
mkdir -p "$COMBINED_DIR" target/jacoco-artifacts

declare -A RESULTS

for BROWSER in "${BROWSERS[@]}"; do
  KEY="SAHMAT-${BROWSER}"
  echo ""
  echo "==================================================================="
  echo " Running SAHMAT ${SUITE_TYPE} suite — browser: ${BROWSER}"
  echo "==================================================================="

  mvn -B test \
    -Dsite=SAHMAT \
    -DsuiteXmlFile="$SUITE_FILE" \
    -Dbrowser="$BROWSER" \
    -Dheadless="$HEADLESS" \
    -Dretry.count="$RETRY_COUNT" \
    -Dallure.results.directory="target/allure-results/${KEY}" \
    -Dsurefire.reportsDirectory="target/surefire-reports/${KEY}" \
    -Dself-healing.report.path="target/self-healing/${KEY}-healing-report.json" \
    -Djacoco.destFile="target/jacoco-artifacts/${KEY}.exec" \
    -Dmaven.test.failure.ignore=true \
    $EXTRA_ARGS
  EXIT_CODE=$?
  RESULTS[$BROWSER]=$EXIT_CODE

  # Merge this browser's Allure results into the combined directory.
  # Allure's own result files are UUID-named, so copying multiple
  # browsers' files into one directory is a safe merge — but
  # environment.properties/categories.json (written once per JVM by
  # AllureEnvironmentWriter) are NOT distinguishable that way, so they're
  # namespaced per browser here rather than blindly overwritten three
  # times in a row.
  if [[ -d "target/allure-results/${KEY}" ]]; then
    find "target/allure-results/${KEY}" -maxdepth 1 -type f \
      \( -name "*-result.json" -o -name "*-container.json" -o -name "*-attachment*" \) \
      -exec cp {} "$COMBINED_DIR/" \;
    if [[ -f "target/allure-results/${KEY}/environment.properties" ]]; then
      cp "target/allure-results/${KEY}/environment.properties" \
        "$COMBINED_DIR/environment-${BROWSER}.properties"
    fi
  fi
done

echo ""
echo "==================================================================="
echo " SAHMAT ${SUITE_TYPE} — all browsers finished"
echo "==================================================================="
OVERALL_EXIT=0
for BROWSER in "${BROWSERS[@]}"; do
  CODE="${RESULTS[$BROWSER]:-1}"
  if [[ "$CODE" -eq 0 ]]; then
    echo "  [✓] ${BROWSER}: PASSED"
  else
    echo "  [✗] ${BROWSER}: FAILED (exit ${CODE})"
    OVERALL_EXIT=1
  fi
done

echo ""
echo "Reports:"
echo "  Extent (per browser) — target/extent-reports/SAHMAT/<browser>/${SUITE_TYPE}/index.html"
echo "  Allure (per browser) — allure serve target/allure-results/SAHMAT-<browser>"
echo "  Allure (combined)    — allure serve ${COMBINED_DIR}"
echo "                     or  mvn allure:report -Dallure.results.directory=${COMBINED_DIR}"

exit $OVERALL_EXIT
