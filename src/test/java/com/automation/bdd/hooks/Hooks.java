package com.automation.bdd.hooks;

import com.automation.core.api.ApiClient;
import com.automation.core.budget.PerfBudgetEnforcer;
import com.automation.core.budget.PerfSamples;
import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.utils.ScreenshotUtil;
import com.automation.core.verify.VerificationError;
import com.automation.core.verify.Verify;
import com.automation.mobile.core.AppiumDriverFactory;
import com.automation.sites.listeners.QuarantineHandler;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;

/**
 * Cucumber lifecycle for ANY site. The site comes from {@code -Dsite=<name>} exactly like the
 * plain TestNG suites; what a scenario needs is chosen by its tag:
 *
 * <ul>
 *   <li>{@code @ui}     - browser via DriverFactory (Chrome/Firefox/Edge/Safari, Grid, headless...)
 *       and navigation to the site's {@code url}.</li>
 *   <li>{@code @mobile} - Appium session via AppiumDriverFactory (site defaults to "mobile").</li>
 *   <li>{@code @api}    - no driver; resets config and configures ApiClient like BaseApiTest.</li>
 * </ul>
 *
 * One driver per scenario, held in a ThreadLocal so scenarios can run in parallel (see
 * CucumberRunnerTest). Cucumber cannot extend BaseTest/MobileBaseTest, so their setup is
 * mirrored here.
 */
public class Hooks {

    private static final Logger logger = LoggerFactory.getLogger(Hooks.class);
    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();

    /**
     * One emulator/device can only hold one Appium session: a second session starting on the
     * same device restarts the UiAutomator2 server and kills the first ("NoSuchSession"). So
     * @mobile scenarios take this permit for their whole duration, which serialises them even
     * when the suite XML runs scenarios in parallel. Raise the permits only if you really have
     * several devices behind this Appium server.
     */
    private static final Semaphore MOBILE_DEVICE = new Semaphore(1);
    private static final ThreadLocal<Boolean> HOLDS_DEVICE = new ThreadLocal<>();

    /** Used by step definitions (via {@link com.automation.bdd.steps.StepBase}). */
    public static WebDriver driver() {
        WebDriver d = DRIVER.get();
        if (d == null) {
            throw new IllegalStateException(
                "No WebDriver for this scenario - tag it @ui or @mobile so Hooks starts one.");
        }
        return d;
    }

    @Before(value = "@ui", order = 0)
    public void startBrowser(Scenario scenario) {
        selectSite("demoqa");
        logger.info("Starting @ui scenario '{}' on site '{}'", scenario.getName(), ConfigReader.getActiveSite());
        WebDriver webDriver = DriverFactory.createDriver();
        DRIVER.set(webDriver);
        Verify.bindDriver(Hooks::driver);
        // Timed like BasePage.navigateTo() so BDD UI scenarios are held to the page-load budget too.
        long startedNanos = System.nanoTime();
        webDriver.get(ConfigReader.get("url"));
        PerfSamples.recordPageLoad(ConfigReader.get("url"), (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    @Before(value = "@mobile", order = -10)
    public void reserveMobileDevice() {
        MOBILE_DEVICE.acquireUninterruptibly();
        HOLDS_DEVICE.set(Boolean.TRUE);
    }

    /** Runs last of all After hooks (lowest order), even when startMobile failed. */
    @After(value = "@mobile", order = -100)
    public void releaseMobileDevice() {
        if (Boolean.TRUE.equals(HOLDS_DEVICE.get())) {
            HOLDS_DEVICE.remove();
            MOBILE_DEVICE.release();
        }
    }

    @Before(value = "@mobile", order = 0)
    public void startMobile(Scenario scenario) {
        selectSite("mobile");
        logger.info("Starting @mobile scenario '{}'", scenario.getName());
        // App launch timed for the screen budget, same as MobileBaseTest.setUp().
        long launchStartedNanos = System.nanoTime();
        DRIVER.set(AppiumDriverFactory.createDriver());
        PerfSamples.recordScreen("app launch", (System.nanoTime() - launchStartedNanos) / 1_000_000L);
        Verify.bindDriver(Hooks::driver);
    }

    @Before(value = "@api", order = 0)
    public void startApi(Scenario scenario) {
        logger.info("Starting @api scenario '{}'", scenario.getName());
        ConfigReader.reset();   // same as BaseApiTest.setUpApiClient()
        ApiClient.configure();
    }

    /**
     * Verify's CONTINUE_ON_FAILURE results are turned into a failure by TestListener only after
     * the whole runScenario method returns, i.e. after the scenario's own After hooks - so apply
     * them here too, for every scenario type, so the scenario itself is reported as failed.
     * Runs BEFORE stopBrowser (After hooks run highest order first) so the screenshot still
     * sees the page.
     */
    @After(order = 100)
    public void applyVerifications(Scenario scenario) {
        // Quarantined scenario: the TestNG result becomes SKIPPED (see QuarantineHandler), but the
        // Allure result written from Cucumber's own events stays failed - label it so it can be filtered.
        QuarantineHandler.markScenario(scenario.getName(), String.valueOf(scenario.getUri()),
            scenario.getSourceTagNames());
        try {
            // Slow pages / API calls of this scenario count as verification failures too.
            PerfBudgetEnforcer.enforce(null);
            Verify.Outcome outcome = Verify.drain();
            if (!outcome.failures().isEmpty()) {
                throw new VerificationError(outcome.failures());
            }
        } finally {
            Verify.unbindDriver();
        }
    }

    @After(value = "@ui or @mobile", order = 0)
    public void stopDriver(Scenario scenario) {
        WebDriver d = DRIVER.get();
        if (d == null) {
            return;
        }
        try {
            if (scenario.isFailed()) {
                try {
                    byte[] png = ScreenshotUtil.captureScreenshotAsBytes(d);
                    if (png != null) {
                        scenario.attach(png, "image/png", "Failure screenshot");
                    }
                } catch (Exception e) {
                    logger.warn("Could not capture failure screenshot: {}", e.getMessage());
                }
            }
        } finally {
            try {
                if (scenario.getSourceTagNames().contains("@mobile")) {
                    d.quit();
                } else {
                    DriverFactory.quitDriver(d);
                }
            } catch (Exception e) {
                logger.warn("driver quit failed: {}", e.getMessage());
            } finally {
                DRIVER.remove();
            }
        }
    }

    /** Same "reset only if the site changed" rule BaseTest/MobileBaseTest use. */
    private static void selectSite(String defaultSite) {
        String requestedSite = System.getProperty("site", defaultSite);
        String currentSite = ConfigReader.getActiveSite();
        if (currentSite == null || !currentSite.equals(requestedSite)) {
            ConfigReader.reset();
        }
    }
}
