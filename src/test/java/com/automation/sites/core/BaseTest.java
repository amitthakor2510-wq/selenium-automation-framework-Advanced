package com.automation.sites.core;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.report.AllureEnvironmentWriter;
import com.automation.core.utils.HumanActions;
import com.automation.sites.listeners.TestListener;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

/**
 * Every site's test classes extend this. Site is selected via
 * -Dsite=<siteName> (see ConfigReader); the driver navigates to
 * whatever "url" resolves to for that site's config file.
 *
 * NOTE: TestListener (a plain ITestListener) is registered here via
 * @Listeners rather than in testng-suites/*.xml <listeners>. Registering
 * it through the suite XML puts it in a different position relative to
 * Allure's auto-registered AllureTestNg listener, so by the time our
 * onTestFailure/onTestSuccess ran, Allure had already closed the test
 * case and Allure.addAttachment() silently dropped the screenshot
 * (Allure.getLifecycle().getCurrentTestCase() was already empty).
 * Declaring the listener via annotation instead fixes the ordering so
 * our attachment calls land while the test case is still open.
 *
 * RetryListener is deliberately NOT here, and must stay out. It's an
 * IAnnotationTransformer, not an ITestListener — TestNG needs to know
 * annotation transformers before it parses @Test annotations across the
 * suite (retry count, groups, enabled, etc.), which happens before
 * TestNG has even discovered this class's @Listeners annotation. Put
 * here, RetryListener.transform() silently never runs (no error, no
 * retries, no clue) — same class of ordering bug as the Allure
 * onStart() issue below, just for a different listener type. It's
 * registered instead in each testng-suites/*.xml's <listeners> block,
 * which is early enough for TestNG to actually pick it up.
 */
@Listeners({TestListener.class})
public class BaseTest implements DriverProvider {

    // Thread-safe driver for parallel execution
    protected static final ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        // Writes environment.properties/categories.json into
        // target/allure-results once per JVM. This USED to be called from
        // TestListener.onStart(ITestContext) — but that's a <test>-level
        // callback, and TestListener is only registered via @Listeners on
        // this class, not via suite XML or ServiceLoader. TestNG fires
        // onStart() for a <test> before it discovers class-level
        // @Listeners annotations declared on test classes within that
        // <test>, so that call was silently never executing at all (no
        // exception, no warning — just dead code), which is why Allure's
        // "Environment"/"Categories" report widgets were always empty.
        // @BeforeMethod always runs regardless of listener timing, so call
        // it from here instead; writeOnce()'s internal guard keeps this
        // cheap even though it now runs before every test method.
        AllureEnvironmentWriter.writeOnce();

        // Only reset if the site actually changed (multi-site runs).
        // For single-site runs (the normal case) ConfigReader stays loaded.
        String currentSite = ConfigReader.getActiveSite();
        String requestedSite = System.getProperty("site", "demoqa");
        if (currentSite == null || !currentSite.equals(requestedSite)) {
            ConfigReader.reset();
        }
        WebDriver webDriver = DriverFactory.createDriver();
        driver.set(webDriver);
        getDriver().get(ConfigReader.get("url"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (getDriver() != null) {
            try {
                getDriver().quit();
            } catch (Exception e) {
                // Previously swallowed silently — that hid real quit()
                // failures (e.g. attaching to an already-running browser
                // instance) that left the window open with no error shown.
                // Log it so a failed teardown is visible instead of silent.
                java.util.logging.Logger.getLogger(BaseTest.class.getName())
                    .warning("[BaseTest] driver.quit() failed: " + e.getMessage());
            } finally {
                driver.remove(); // Important for memory cleanup
            }
        }
    }

    public WebDriver getDriver() {
        return driver.get();
    }

    /**
     * Human pause between individual UI steps within a test. Prefer using
     * HumanActions.click()/type() from Page Objects instead of calling this
     * directly - those already pause automatically before each interaction.
     * This is here for the rare case a test needs a deliberate beat that
     * isn't tied to a single click/type (e.g. waiting for an animation to
     * settle before asserting).
     */
    protected void humanPause() {
        HumanActions.pause();
    }
}
