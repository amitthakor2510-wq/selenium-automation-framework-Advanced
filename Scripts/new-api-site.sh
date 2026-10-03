#!/usr/bin/env bash
# Usage: ./Scripts/new-api-site.sh <sitename> <base-url>
# Example: ./Scripts/new-api-site.sh mysite https://api.mysite.com
#
# Scaffolds an API-only site (no browser, no page objects) — the automated
# version of the manual checklist in docs/extending.md's "Adding a New
# API-Only Site" section. `jsonplaceholder` is the reference example that
# checklist was written from; this script produces the same files that
# checklist walks through by hand, in the same order, so re-reading that
# section still explains what each step below is for.
#
# Sibling to Scripts/new-site.sh, which scaffolds a full browser-driven UI
# site across all three testing styles this framework supports. That script
# doesn't fit an API-only site (there's no DOM, so no Page Object, no
# object repository, no keyword/file-driven UI scenarios) — this one is
# deliberately smaller and produces exactly what an HTTP-only test needs.

set -e

SITE="$1"
URL="$2"

if [[ -z "$SITE" || -z "$URL" ]]; then
  echo "Usage: $0 <sitename> <base-url>"
  exit 1
fi

# SITE becomes a Java package segment (com.automation.sites.${SITE}.*) and
# feeds a ${SITE^} class name below — both are illegal with anything other
# than lowercase letters/digits/underscore, and a leading digit breaks the
# class name specifically. Caught here for one clear message instead of a
# confusing javac error three files deep once compile is attempted. (Unlike
# new-site.sh, an API-only site's key is sometimes mixed-case by convention
# — see SAHMAT — but a *new* one scaffolded by this script always gets a
# lowercase key, matching jsonplaceholder; rename by hand afterwards if you
# specifically need a mixed-case key, and update SiteMapper.SITE_TEST_PACKAGE
# to match, same as SAHMAT's own comment there describes.)
if [[ ! "$SITE" =~ ^[a-z][a-z0-9_]*$ ]]; then
  echo "[✗] '${SITE}' isn't a valid site name — it becomes a Java package/class"
  echo "    name, so it must be lowercase letters, digits, and underscores only,"
  echo "    starting with a letter (e.g. 'mysite', 'billing_api')."
  exit 1
fi

# Guard against overwriting an existing site by accident — every generator
# below uses `>` (clobber), so a re-run against an already-scaffolded site
# would silently wipe out any real work added since. Fail fast instead.
if [[ -f "src/test/resources/config/${SITE}.properties" ]]; then
  echo "[✗] src/test/resources/config/${SITE}.properties already exists — '${SITE}' looks already scaffolded."
  echo "    Remove it first (and the other generated files) if you really want to regenerate, or pick a new site name."
  exit 1
fi

CLASS="${SITE^}ApiTest"

# =========================================================================
# 1/1b. Register the site — SiteRegistry (validated before any test method
#    runs; unregistered sites are rejected outright, by design) and
#    SiteMapper (Test Impact Analysis's own small, dependency-free mirror
#    of SiteRegistry.KNOWN_SITES — see SiteMapper's own Javadoc: "Update
#    both places together when a new site is added"). Missing the second
#    one doesn't fail loudly — TIA just silently falls back to its
#    site-blind "unsafe/full suite" decision for every change touching
#    this site. This exact gap shipped unnoticed twice before this script
#    existed (SAHMAT, then jsonplaceholder — see SiteMapper.java's own
#    comment), which is the whole reason this step is automated here.
#    new SiteDefinition(false) — false = no object repository required,
#    correct for every API-only site (no locators, no DOM).
# =========================================================================
SITE_REGISTRY="src/main/java/com/automation/core/config/SiteRegistry.java"
if [[ -f "$SITE_REGISTRY" ]] && grep -q "KNOWN_SITES = Map.ofEntries(" "$SITE_REGISTRY"; then
  sed -i "s/KNOWN_SITES = Map.ofEntries(/KNOWN_SITES = Map.ofEntries(\n        Map.entry(\"${SITE}\", new SiteDefinition(false)),/" "$SITE_REGISTRY"
  echo "[✓] Registered '${SITE}' in SiteRegistry.KNOWN_SITES (requiresObjectRepository=false)"
else
  echo "[✗] Could not find KNOWN_SITES in ${SITE_REGISTRY} — register '${SITE}' there by hand before running any test:"
  echo "        Map.entry(\"${SITE}\", new SiteDefinition(false)),"
fi

SITE_MAPPER="src/main/java/com/automation/core/tia/SiteMapper.java"
if [[ -f "$SITE_MAPPER" ]] && grep -q "SITE_TEST_PACKAGE.put(" "$SITE_MAPPER"; then
  # awk, not sed: must only insert before the FIRST bare "}" that follows
  # "static {" (the static initializer's own closing brace) — a plain
  # "closing brace at start of line" sed pattern also matches the
  # constructor's closing brace right below it and corrupts the file.
  # (Same logic as new-site.sh's SiteMapper step — kept identical since
  # both scripts edit the same static block.)
  awk -v site="${SITE}" '
    /static[ \t]*\{/ { in_static = 1 }
    in_static && /^[ \t]*}[ \t]*$/ {
      print "        SITE_TEST_PACKAGE.put(\"" site "\", \"com.automation.sites." site "\");"
      print $0
      in_static = 0
      next
    }
    { print }
  ' "$SITE_MAPPER" > "${SITE_MAPPER}.tmp" && mv "${SITE_MAPPER}.tmp" "$SITE_MAPPER"
  if grep -c "SITE_TEST_PACKAGE.put(\"${SITE}\"" "$SITE_MAPPER" | grep -q "^1\$"; then
    echo "[✓] Registered '${SITE}' in SiteMapper.SITE_TEST_PACKAGE (com.automation.sites.${SITE})"
  else
    echo "[✗] Could not confirm '${SITE}' was added to ${SITE_MAPPER} — add this line by hand inside its static block:"
    echo "        SITE_TEST_PACKAGE.put(\"${SITE}\", \"com.automation.sites.${SITE}\");"
  fi
else
  echo "[✗] Could not find SITE_TEST_PACKAGE in ${SITE_MAPPER} — register '${SITE}' there by hand before relying on Test Impact Analysis:"
  echo "        SITE_TEST_PACKAGE.put(\"${SITE}\", \"com.automation.sites.${SITE}\");"
fi

# =========================================================================
# 2. Config file
# =========================================================================
cat > "src/test/resources/config/${SITE}.properties" <<EOF
# =========================================================
# SITE PROJECT: ${SITE} (API-only — no browser, no page objects)
# Only put things here that are DIFFERENT from global.properties
# =========================================================

site.name=${SITE}
url=${URL}
EOF
echo "[✓] Created config/${SITE}.properties"

# =========================================================================
# 3. Enable it + tag it API-only — pipeline-config.properties. The
#    "type=api" line is what tells Scripts/enabled-sites.sh --browser-only
#    (used by GitHub Actions' matrix-setup job, and the Jenkins/GitLab
#    equivalents) to exclude this site from the UI browser test matrix —
#    without it, CI would try to spin up a Selenium session against a site
#    with no page objects to test.
# =========================================================================
PIPELINE_CONFIG="pipeline-config.properties"
if [[ -f "$PIPELINE_CONFIG" ]] && grep -q "^site\.${SITE}\.enabled=" "$PIPELINE_CONFIG"; then
  echo "[i] '${SITE}' already has a line in ${PIPELINE_CONFIG} — left as-is."
elif [[ -f "$PIPELINE_CONFIG" ]]; then
  {
    echo "site.${SITE}.enabled=true"
    echo "site.${SITE}.type=api"
  } >> "$PIPELINE_CONFIG"
  echo "[✓] Registered '${SITE}' in ${PIPELINE_CONFIG} (enabled=true, type=api)"
else
  echo "[✗] ${PIPELINE_CONFIG} not found at repo root — add these lines by hand once it exists:"
  echo "        site.${SITE}.enabled=true"
  echo "        site.${SITE}.type=api"
fi

# =========================================================================
# 4. Test class — extends BaseApiTest, uses ApiClient/ApiAssertions, same
#    pattern docs/extending.md's checklist itself recommends. The endpoint
#    below ("/") is a generic placeholder that works against any base URL
#    with zero setup, same spirit as new-site.sh's css:body placeholder —
#    replace it with a real endpoint once you've looked at ${SITE}'s API.
# =========================================================================
mkdir -p "src/test/java/com/automation/sites/${SITE}/tests"
cat > "src/test/java/com/automation/sites/${SITE}/tests/${CLASS}.java" <<EOF
package com.automation.sites.${SITE}.tests;

import com.automation.core.api.ApiAssertions;
import com.automation.core.api.ApiClient;
import com.automation.sites.core.BaseApiTest;
import io.restassured.response.Response;
import org.testng.annotations.Test;

/**
 * ${SITE^} — API-only site, no browser/page objects (see config/${SITE}.properties
 * and SiteRegistry.KNOWN_SITES: requiresObjectRepository=false).
 *
 * Run with: -Dsite=${SITE} -DsuiteXmlFile=testng-suites/api-tests-${SITE}.xml
 *
 * TODO: "/" is a generic placeholder endpoint so this compiles and runs
 * against any base URL with zero manual setup. Replace it, and the 200
 * assertion, with a real ${SITE} endpoint and expected status once you've
 * inspected the actual API. See JsonPlaceholderApiTest for a fuller
 * example (ApiAssertions.assertMatchesSchema, ApiRetry, auth providers).
 */
public class ${CLASS} extends BaseApiTest {

    @Test(groups = {"smoke", "api"},
        description = "${SITE^} - Base URL responds")
    public void baseUrl_ShouldRespond() {
        Response response = ApiClient.get("/");
        ApiAssertions.assertStatus(response, 200);
    }
}
EOF
echo "[✓] Created tests/${CLASS}.java stub"

# =========================================================================
# 5. Suite file — its own file, not a second <test> block inside an
#    existing suite: -Dsite is a single JVM-wide system property (see
#    ConfigReader), so a suite mixing two different sites' API test
#    classes can only ever correctly resolve one of them per run. See
#    testng-suites/api-tests.xml's own comment for the full reasoning.
# =========================================================================
mkdir -p testng-suites
cat > "testng-suites/api-tests-${SITE}.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<!-- API tests for the standalone, API-only "${SITE}" site — no UI, no page objects,
     no object repository (see SiteRegistry.KNOWN_SITES). Kept as its own suite file rather
     than a second <test> block inside api-tests.xml because -Dsite is a single JVM-wide
     system property (see ConfigReader's class javadoc) — this suite must run as
     -Dsite=${SITE}, api-tests.xml's demoqa suite must run as -Dsite=demoqa (the
     default), and no single \`mvn test\` invocation can satisfy both at once. -->
<suite name="API Tests Suite - ${SITE}" parallel="classes" thread-count="1">

  <listeners>
    <listener class-name="com.automation.sites.listeners.RetryListener"/>
  </listeners>

  <test name="${SITE^} API Tests" preserve-order="false">
    <groups>
      <run>
        <include name="api"/>
      </run>
    </groups>

    <classes>
      <class name="com.automation.sites.${SITE}.tests.${CLASS}"/>
    </classes>

  </test>

</suite>
EOF
echo "[✓] Created testng-suites/api-tests-${SITE}.xml"

# =========================================================================
# 6. (Optional, done here) Schema folder — JSON schema contract validation
#    via ApiAssertions.assertMatchesSchema(...). Left empty; the checklist
#    marks this step optional and there's no real schema to generate
#    without seeing the actual API responses.
# =========================================================================
mkdir -p "src/test/resources/schemas/${SITE}"
echo "[i] Created empty src/test/resources/schemas/${SITE}/ — add a *.json schema here if you want contract validation (optional, see ApiAssertions.assertMatchesSchema)."

# =========================================================================
# 7. (Optional) Wire into CI — GitHub Actions only, matching the scope of
#    docs/extending.md's own step 8, which names only this file: a single
#    matrix entry in the api-tests job. GitLab CI's equivalent job builds
#    its suite file from a shell `case` statement per site rather than a
#    flat list (see .gitlab-ci.yml's own api-tests job), and Jenkinsfile
#    does the same via a Groovy map literal — both are safe to hand-edit
#    but risky to patch unattended with a one-size sed/awk, so this script
#    prints the exact lines instead of touching either file, the same way
#    new-site.sh itself defers to a human when it can't find a safe anchor.
# =========================================================================
GITHUB_CI=".github/workflows/github-ci.yml"
ANCHOR="          - site: jsonplaceholder
            suiteFile: testng-suites/api-tests-jsonplaceholder.xml"
if [[ -f "$GITHUB_CI" ]] && grep -qF "$ANCHOR" "$GITHUB_CI"; then
  if grep -qF "suiteFile: testng-suites/api-tests-${SITE}.xml" "$GITHUB_CI"; then
    echo "[i] '${SITE}' already in ${GITHUB_CI}'s api-tests matrix — left as-is."
  else
    python3 - "$GITHUB_CI" "$SITE" <<'PYEOF'
import sys
path, site = sys.argv[1], sys.argv[2]
anchor = ('          - site: jsonplaceholder\n'
          '            suiteFile: testng-suites/api-tests-jsonplaceholder.xml\n')
addition = f'          - site: {site}\n            suiteFile: testng-suites/api-tests-{site}.xml\n'
text = open(path).read()
assert text.count(anchor) == 1, "anchor not found exactly once"
text = text.replace(anchor, anchor + addition, 1)
open(path, 'w').write(text)
PYEOF
    echo "[✓] Added '${SITE}' to ${GITHUB_CI}'s api-tests job matrix"
  fi
else
  echo "[i] Could not find the expected jsonplaceholder anchor in ${GITHUB_CI} — add this entry to the api-tests job's matrix.include list by hand:"
  echo "        - site: ${SITE}"
  echo "          suiteFile: testng-suites/api-tests-${SITE}.xml"
fi

echo ""
echo "[i] GitLab CI and Jenkins wiring is left for you (each builds its suite"
echo "    file from a per-site case/map, not a flat list — hand-editing is"
echo "    safer than a scripted patch there). Add, matching the jsonplaceholder"
echo "    entries already in each file:"
echo "      .gitlab-ci.yml   — api-tests job: add '${SITE}' to 'SITE: [ ... ]',"
echo "                         a matching rules: gate, and a case arm mapping"
echo "                         ${SITE} -> testng-suites/api-tests-${SITE}.xml"
echo "      Jenkinsfile      — the API Tests stage's per-site suiteFile map,"
echo "                         plus resultDirs += ['${SITE}-api']"

echo ""
echo "✅ New API-only site '${SITE}' scaffolded and registered everywhere a"
echo "   config-driven site needs to be (SiteRegistry, SiteMapper,"
echo "   pipeline-config.properties). Just write real code from here:"
echo "   1. Edit tests/${CLASS}.java with a real endpoint + assertions"
echo "   2. (Optional) Add a JSON schema under schemas/${SITE}/"
echo "   3. Run it:  mvn test -Dsite=${SITE} -DsuiteXmlFile=testng-suites/api-tests-${SITE}.xml"
