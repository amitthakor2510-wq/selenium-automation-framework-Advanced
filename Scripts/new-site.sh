#!/usr/bin/env bash
# Usage: ./scripts/new-site.sh <sitename> <base-url>
# Example: ./scripts/new-site.sh indianrail https://nationalrail.co.uk
#
# Scaffolds a new site across all THREE testing styles this framework
# supports, so a new site is never left with only one:
#   1. Standard (Page Object + TestNG)  — a real Selenium test driving the
#      DOM directly through a Page Object, same as every existing site test.
#   2. Keyword-driven                    — steps live as rows in a CSV,
#      resolved against an ObjectRepository locator file, run by KeywordEngine.
#      No Java needed to add a new scenario.
#   3. File-driven (data-driven)         — one Java test method, many rows
#      of input data from a CSV/Excel/JSON/YAML file via DataProviderFactory.
#
# All three are wired into the SAME generated regression/smoke suite XML
# (they're just different @Test groups within the same site package), so
# `mvn test -Dsite=<site> -DsuiteXmlFile=testng-suites/<site>-regression.xml`
# runs all three without any extra CI/suite wiring.

set -e

SITE="$1"
URL="$2"

if [[ -z "$SITE" || -z "$URL" ]]; then
  echo "Usage: $0 <sitename> <base-url>"
  exit 1
fi

# SITE becomes a Java package segment (com.automation.sites.${SITE}.*)
# and feeds a ${SITE^} class name (${CLASS} below) — both are illegal
# with anything other than lowercase letters/digits/underscore, and a
# leading digit breaks the class name specifically. Catching this here
# gives one clear message instead of a confusing javac syntax error
# three files deep once compile is attempted.
if [[ ! "$SITE" =~ ^[a-z][a-z0-9_]*$ ]]; then
  echo "[✗] '${SITE}' isn't a valid site name — it becomes a Java package/class"
  echo "    name, so it must be lowercase letters, digits, and underscores only,"
  echo "    starting with a letter (e.g. 'indianrail', 'flight_track')."
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

CLASS="${SITE^}HomePage"

# =========================================================================
# 1. Config
# =========================================================================
cat > "src/test/resources/config/${SITE}.properties" <<EOF
site.name=${SITE}
url=${URL}
EOF
echo "[✓] Created config/${SITE}.properties"

# =========================================================================
# 2. Regression suite — scans the whole site package, so it picks up the
#    standard test, the keyword-driven test, and the data-driven test below
#    with no per-type suite wiring needed.
# =========================================================================
cat > "testng-suites/${SITE}-regression.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Regression Suite" parallel="none">
    <test name="${SITE^} Regression Tests" preserve-order="true">
        <groups>
            <run><include name="regression"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-regression.xml"

# =========================================================================
# 3. Smoke suite
# =========================================================================
cat > "testng-suites/${SITE}-smoke.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Smoke Suite" parallel="none">
    <test name="${SITE^} Smoke Tests" preserve-order="true">
        <groups>
            <run><include name="smoke"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-smoke.xml"

# =========================================================================
# 3b. Safari variants — parallel="none" is required, not a style choice:
#     only one SafariDriver session may exist on a machine at a time (see
#     DriverFactory.createSafariDriver()'s javadoc), so a thread-count>1
#     suite fails outright under browser=safari. Same groups/packages as
#     the plain suites above; run with -Dbrowser=safari (macOS only, no
#     -Dheadless — Safari has no headless mode). See docs/configuration.md#-safari.
# =========================================================================
cat > "testng-suites/${SITE}-safari-regression.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Safari Regression Suite" parallel="none">
    <test name="${SITE^} Safari Regression Tests" preserve-order="true">
        <groups>
            <run><include name="regression"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-safari-regression.xml"

cat > "testng-suites/${SITE}-safari-smoke.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Safari Smoke Suite" parallel="none">
    <test name="${SITE^} Safari Smoke Tests" preserve-order="true">
        <groups>
            <run><include name="smoke"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-safari-smoke.xml"

mkdir -p "src/main/java/com/automation/sites/${SITE}/pages"
mkdir -p "src/test/java/com/automation/sites/${SITE}/tests"
mkdir -p "src/test/resources/testdata/keyword"
mkdir -p "src/test/resources/objectrepository"

# =========================================================================
# 4. TYPE 1 — Standard: Page Object + TestNG test
# =========================================================================
cat > "src/main/java/com/automation/sites/${SITE}/pages/${CLASS}.java" <<EOF
package com.automation.sites.${SITE}.pages;

import com.automation.core.base.BasePage;
import org.openqa.selenium.WebDriver;

public class ${CLASS} extends BasePage {

    public ${CLASS}(WebDriver driver) {
        super(driver);
    }

    public void navigateToHome() {
        navigateTo("");
    }
}
EOF
echo "[✓] Created pages/${CLASS}.java stub"

TEST="${SITE^}HomeTest"
cat > "src/test/java/com/automation/sites/${SITE}/tests/${TEST}.java" <<EOF
package com.automation.sites.${SITE}.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.${SITE}.pages.${CLASS};
import io.qameta.allure.*;
import org.testng.Assert;
import org.testng.annotations.Test;

@Feature("${SITE^}")
public class ${TEST} extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "${SITE^} - Verify Home Page Loads")
    @Story("Home Page")
    @Severity(SeverityLevel.CRITICAL)
    public void verifyHomePageLoads() {
        ${CLASS} page = new ${CLASS}(getDriver());
        page.navigateToHome();
        Assert.assertFalse(getDriver().getTitle().isEmpty(),
                "Page title should not be empty");
    }
}
EOF
echo "[✓] Created tests/${TEST}.java stub"

# =========================================================================
# 5. TYPE 2 — Keyword-driven: ObjectRepository + keyword CSV + test class
# =========================================================================
cat > "src/test/resources/objectrepository/${SITE}.properties" <<EOF
# =========================================================
# OBJECT REPOSITORY: ${SITE}
# One locator per line: <key> = <type>:<value>
# Supported types: id, name, css, xpath, class, linktext, partiallinktext, tag
# Keep keys in "<site>.<screen>.<element>" style so they read naturally in
# a keyword script's locatorKey column.
#
# TODO: replace the placeholder below with a real locator from the
# ${SITE} home page once you've inspected it (DevTools > Elements).
# =========================================================

${SITE}.home.placeholder=css:body
EOF
echo "[✓] Created objectrepository/${SITE}.properties stub"

cat > "src/test/resources/testdata/keyword/${SITE}_home_keywords.csv" <<EOF
testCase,stepNo,keyword,locatorKey,testData,expected,description
TC01_HomePageLoads,1,NAVIGATE,,,,Go to ${SITE} home page
TC01_HomePageLoads,2,VERIFY_DISPLAYED,${SITE}.home.placeholder,,,Home page body should be visible
EOF
echo "[✓] Created testdata/keyword/${SITE}_home_keywords.csv stub"

KEYWORD_TEST="KeywordDriven${SITE^}HomeTest"
cat > "src/test/java/com/automation/sites/${SITE}/tests/${KEYWORD_TEST}.java" <<EOF
package com.automation.sites.${SITE}.tests;

import com.automation.sites.core.KeywordTestBase;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

/**
 * Same home-page smoke check as ${TEST}, expressed as a keyword-driven
 * script instead of Java: every step lives in
 * src/test/resources/testdata/keyword/${SITE}_home_keywords.csv, resolved
 * against locators in src/test/resources/objectrepository/${SITE}.properties.
 *
 * Adding a new scenario is a new block of rows in that CSV — no new Java
 * method required unless it needs assertions the keyword vocabulary
 * doesn't already cover (see Keyword.java).
 *
 * TODO: the "placeholder" locator (css:body) and NAVIGATE step above are
 * intentionally generic so this compiles and runs against any site with
 * zero manual setup. Replace them with a real ${SITE} element once you've
 * inspected the actual home page.
 */
@Feature("${SITE^}")
@Story("Home Page - Keyword Driven")
public class ${KEYWORD_TEST} extends KeywordTestBase {

    private static final String OBJECT_REPO = "objectrepository/${SITE}.properties";
    private static final String SCRIPT = "src/test/resources/testdata/keyword/${SITE}_home_keywords.csv";

    @Test(groups = {"regression", "keyword-driven"},
        description = "${SITE^} - Home page loads, driven entirely by keyword script")
    public void homePageLoads() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC01_HomePageLoads");
    }
}
EOF
echo "[✓] Created tests/${KEYWORD_TEST}.java stub"

# =========================================================================
# 6. TYPE 3 — File-driven (data-driven): data file + DataProviderFactory test
# =========================================================================
cat > "src/test/resources/testdata/${SITE}_home.csv" <<EOF
scenario,notes
default,First-visit smoke check
EOF
echo "[✓] Created testdata/${SITE}_home.csv stub"

DATA_TEST="${SITE^}HomeDataDrivenTest"
cat > "src/test/java/com/automation/sites/${SITE}/tests/${DATA_TEST}.java" <<EOF
package com.automation.sites.${SITE}.tests;

import com.automation.core.data.DataProviderFactory;
import com.automation.core.data.DataRow;
import com.automation.sites.core.BaseTest;
import com.automation.sites.${SITE}.pages.${CLASS};
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Same home-page smoke check as ${TEST}, but reading its scenario data from
 * src/test/resources/testdata/${SITE}_home.csv instead of hardcoding it in
 * Java — swap fromFile(...) for a .json/.yaml/.xlsx path to switch formats,
 * they all produce the same DataRow shape (see LoginDataDrivenTest for a
 * side-by-side example across every supported format).
 *
 * Adding a new scenario is a new row in that CSV — no new Java method
 * required unless the assertions differ per row.
 *
 * TODO: this starts with one placeholder row/column ("scenario"/"notes").
 * Add real columns (e.g. locale, viewport, query params) and use them in
 * verifyHomePageLoads() once you have real per-scenario variations to cover.
 */
@Feature("${SITE^}")
@Story("Home Page - Data Driven")
public class ${DATA_TEST} extends BaseTest {

    @DataProvider(name = "homeScenarios")
    public Object[][] homeScenarios() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/${SITE}_home.csv");
    }

    @Test(
        dataProvider = "homeScenarios",
        groups       = {"regression", "data-driven"},
        description  = "${SITE^} - Home page loads, driven by data file"
    )
    public void verifyHomePageLoads(DataRow row) {
        String notes = row.get("notes");
        System.out.println("  Row " + row.getRowIndex()
            + " | scenario=" + row.get("scenario")
            + (notes.isEmpty() ? "" : " | " + notes));

        ${CLASS} page = new ${CLASS}(getDriver());
        page.navigateToHome();
        Assert.assertFalse(getDriver().getTitle().isEmpty(),
                "Page title should not be empty");
    }
}
EOF
echo "[✓] Created tests/${DATA_TEST}.java stub"

# =========================================================================
# 7. Register with SiteRegistry — this is the one step every other
#    generated file above depends on and none of them can substitute for.
#    SiteRegistry.validate(site) runs before any test method (from
#    ConfigReader.init()) and rejects any site not listed in KNOWN_SITES,
#    by design (see that class's own Javadoc: "add one line to KNOWN_SITES
#    below" is explicitly step 1 of adding a site) — every file this
#    script just generated (config properties, object repository, suite
#    XMLs) would otherwise sit there unused, and the exact `mvn test`
#    command this script prints below would fail immediately with
#    "Site '${SITE}' is not registered in SiteRegistry" before ever
#    reaching a browser. Every site this script scaffolds always gets an
#    objectrepository/${SITE}.properties (step 5 above), so
#    requiresObjectRepository is always true here — that matches what's
#    actually on disk for a script-generated site.
# =========================================================================
SITE_REGISTRY="src/main/java/com/automation/core/config/SiteRegistry.java"
if [[ -f "$SITE_REGISTRY" ]] && grep -q "KNOWN_SITES = Map.of(" "$SITE_REGISTRY"; then
  sed -i "s/KNOWN_SITES = Map.of(/KNOWN_SITES = Map.of(\n        \"${SITE}\", new SiteDefinition(true),/" "$SITE_REGISTRY"
  echo "[✓] Registered '${SITE}' in SiteRegistry.KNOWN_SITES (requiresObjectRepository=true)"
else
  echo "[✗] Could not find KNOWN_SITES in ${SITE_REGISTRY} — register '${SITE}' there by hand before running any test:"
  echo "        \"${SITE}\", new SiteDefinition(true),"
fi

# =========================================================================
# 8. Register with pipeline-config.properties — the master on/off switch
#    read by Scripts/enabled-sites.sh (GitHub Actions' matrix-setup,
#    Jenkins' "Discover Site Projects", GitLab CI's
#    generate-pipeline-config) and by SiteRegistry.validate() for local
#    runs. Without a line here, isEnabled("${SITE}") returns false and
#    validate() rejects the site everywhere (fail-closed default), even
#    though KNOWN_SITES above now recognizes it. New sites start enabled
#    so the exact `mvn test` command this script prints below works
#    immediately.
# =========================================================================
PIPELINE_CONFIG="pipeline-config.properties"
if [[ -f "$PIPELINE_CONFIG" ]] && grep -q "^site\.${SITE}\.enabled=" "$PIPELINE_CONFIG"; then
  echo "[i] '${SITE}' already has a line in ${PIPELINE_CONFIG} — left as-is."
elif [[ -f "$PIPELINE_CONFIG" ]]; then
  echo "site.${SITE}.enabled=true" >> "$PIPELINE_CONFIG"
  echo "[✓] Registered '${SITE}' in ${PIPELINE_CONFIG} (enabled=true)"
else
  echo "[✗] ${PIPELINE_CONFIG} not found at repo root — add this line by hand once it exists:"
  echo "        site.${SITE}.enabled=true"
fi

# GitLab CI only: its parallel:matrix values must be static (rules are
# evaluated before any script runs, so unlike GitHub Actions/Jenkins it
# can't discover sites from a file) — so this script edits .gitlab-ci.yml
# itself, via the two AUTO-GENERATED-SITE-* markers on the `test` job,
# rather than leaving that edit for a human to remember.
GITLAB_CI=".gitlab-ci.yml"
if [[ -f "$GITLAB_CI" ]] && grep -q "AUTO-GENERATED-SITE-LIST" "$GITLAB_CI"; then
  if grep -qE "SITE: \[ [^]]*\b${SITE}\b" "$GITLAB_CI"; then
    echo "[i] '${SITE}' already in .gitlab-ci.yml's SITE: [ ... ] list — left as-is."
  else
    UPPER_SITE=$(echo "$SITE" | tr '[:lower:]-' '[:upper:]_')
    # Append the site into "SITE: [ demoqa, saucedemo, ... ]" — matches
    # regardless of how many sites are already listed, so this stays
    # correct no matter how many times new-site.sh has run before.
    sed -i -E "s/(SITE: \[ [^]]*)\]/\1, ${SITE} ]/" "$GITLAB_CI"
    # Insert a matching rules: gate right after the START marker so a
    # disabled site is skipped the same way demoqa/saucedemo already are.
    sed -i "/# AUTO-GENERATED-SITE-RULES-START/a\\    - if: '\$SITE == \"${SITE}\" \&\& \$SITE_${UPPER_SITE}_ENABLED == \"false\"'\\n      when: never" "$GITLAB_CI"
    echo "[✓] Added '${SITE}' to .gitlab-ci.yml's test job (SITE list + rules: gate)"
  fi
else
  echo "[✗] Could not find the AUTO-GENERATED-SITE-LIST marker in ${GITLAB_CI} —"
  echo "    add '${SITE}' to its test job's SITE: [ ... ] list by hand, plus a"
  echo "    matching rules: gate (copy the demoqa/saucedemo pattern there)."
fi

echo ""
echo "✅ New site '${SITE}' scaffolded across all 3 testing styles, and wired into"
echo "   every pipeline (GitHub Actions, Jenkins, GitLab CI) and local runs — no"
echo "   CI/CD file needs manual editing. Just write real code from here:"
echo "   1. Standard:       edit pages/${CLASS}.java + tests/${TEST}.java"
echo "   2. Keyword-driven: edit objectrepository/${SITE}.properties + testdata/keyword/${SITE}_home_keywords.csv"
echo "   3. File-driven:    edit testdata/${SITE}_home.csv (or swap for .json/.yaml/.xlsx)"
echo "   4. Run everything: mvn test -Dsite=${SITE} -DsuiteXmlFile=testng-suites/${SITE}-regression.xml"