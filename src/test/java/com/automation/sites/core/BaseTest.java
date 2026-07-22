package com.automation.sites.core;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.utils.HumanActions;
import com.automation.sites.listeners.RetryListener;
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
 * NOTE: TestListener/RetryListener are registered here via @Listeners
 * rather than in testng-suites/*.xml <listeners>. Registering a custom
 * ITestListener through the suite XML puts it in a different position
 * relative to Allure's auto-registered AllureTestNg listener, so by the
 * time our onTestFailure/onTestSuccess ran, Allure had already closed
 * the test case and Allure.addAttachment() silently dropped the
 * screenshot (Allure.getLifecycle().getCurrentTestCase() was already
 * empty). Declaring the listener via annotation instead fixes the
 * ordering so our attachment calls land while the test case is still open.
 */
@Listeners({TestListener.class, RetryListener.class})
public class BaseTest implements DriverProvider {

    // Thread-safe driver for parallel execution
    protected static final ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
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
            } catch (Exception ignored) {
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