package com.automation.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.sites.core.BaseTest;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * ============================================================================
 * REFERENCE TEMPLATE — not a real test, never run by any suite.
 * ============================================================================
 * Pairs with TemplatePage.java (src/main/java/com/automation/template/) —
 * read that one first for the Page Object conventions, this one covers the
 * Test class conventions. Same deal: lives in `com.automation.template`,
 * which no testng-suites/*.xml <packages> entry matches, so TestNG never
 * discovers or runs this — but it still compiles and gets Checkstyle-checked
 * on every build, so it can't quietly rot out of date.
 *
 * To build a REAL test: copy this into
 * src/test/java/com/automation/sites/<yoursite>/tests/, rename the class,
 * and replace the example logic. Or use Scripts/new-site.sh to scaffold a
 * whole new site at once — this file is for understanding the pattern.
 * ============================================================================
 */
public class TemplateTest extends BaseTest {

    // Same rule as TemplatePage: named logger per class, name matches the
    // class exactly. Never System.out.println / System.err.println —
    // logger output is what actually shows up correctly attributed in CI
    // console output and gets picked up consistently regardless of which
    // pipeline (Jenkins/GitLab/GitHub) is running it.
    private static final Logger logger = LoggerFactory.getLogger(TemplateTest.class);

    // The page object under test — created fresh per test method in
    // @BeforeMethod below, NOT as a field initializer, because getDriver()
    // isn't valid until BaseTest.setUp() (a @BeforeMethod itself) has run
    // first and actually created the WebDriver for this thread.
    private TemplatePage templatePage;

    @BeforeMethod(alwaysRun = true)
    public void setUpPage() {
        // BaseTest's own @BeforeMethod (setUp()) already ran by the time
        // this fires — TestNG runs @BeforeMethod methods in the order
        // they're declared across the inheritance chain, superclass first.
        // getDriver() is guaranteed non-null here.
        templatePage = new TemplatePage(getDriver());
    }

    // ── groups ───────────────────────────────────────────────────────────
    // Every @Test needs a `groups` attribute — it's how testng-suites/*.xml
    // decides what runs. "smoke" = fast, critical-path only, runs on every
    // PR. "regression" = the fuller suite, runs nightly / pre-release. A
    // test can belong to both if it's both fast AND important enough to
    // gate a PR on. There's no default — a @Test with no groups attribute
    // never gets picked up by ANY suite XML, and just silently never runs.
    //
    // retry is handled automatically for every test in every suite via
    // RetryListener, wired in each testng-suites/*.xml's <listeners> block
    // (see BaseTest.java's class comment for exactly why it has to be
    // wired there and not on the test class itself). You never need to
    // reference RetryAnalyzer or add anything retry-related here.

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Template Page - Verify search returns results")
    public void verifySearchReturnsResults() {
        logger.info("Starting verifySearchReturnsResults");

        templatePage.navigateToTemplatePage();
        templatePage.search("example query");

        int resultCount = templatePage.getResultCount();
        logger.info("Result count: " + resultCount);

        // Assertions belong in the TEST, never in the page object — see
        // TemplatePage's comment on why. Use TestNG's Assert (already
        // imported above), not a third-party assertion library — this
        // framework doesn't pull in AssertJ/Hamcrest/etc., so introducing
        // one in a new test would be an unexplained dependency nobody else
        // in the codebase uses.
        Assert.assertTrue(resultCount > 0,
            "Expected at least one result, got " + resultCount);
    }

    @Test(priority = 2, groups = {"regression"},
        description = "Template Page - Verify first result text is not empty")
    public void verifyFirstResultHasText() {
        templatePage.navigateToTemplatePage();
        templatePage.search("example query");

        String firstResult = templatePage.getFirstResultText();
        logger.info("First result text: " + firstResult);

        Assert.assertFalse(firstResult.isEmpty(), "First result text was empty");
    }

    // "regression"-only, not "smoke": this is exactly the kind of test that
    // belongs in the fuller nightly suite rather than gating every PR — a
    // secondary check on a widget's state, not a critical user path.
    @Test(priority = 3, groups = {"regression"},
        description = "Template Page - Verify search button is present")
    public void verifySearchButtonVisible() {
        templatePage.navigateToTemplatePage();
        Assert.assertTrue(templatePage.isSearchButtonVisible(),
            "Search button should be visible on page load");
    }

    // Nothing to clean up in an @AfterMethod here — BaseTest's own
    // tearDown() (@AfterMethod, alwaysRun = true) already quits the driver
    // and clears the ThreadLocal after every test. Don't duplicate that.
}
