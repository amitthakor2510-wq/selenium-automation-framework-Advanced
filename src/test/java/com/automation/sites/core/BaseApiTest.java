package com.automation.sites.core;

import com.automation.core.api.ApiClient;
import com.automation.core.api.CleanupRegistry;
import com.automation.core.config.ConfigReader;
import com.automation.core.report.ExtentManager;
import com.automation.sites.listeners.TestListener;
import org.testng.ITestContext;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

/**
 * Base for pure-HTTP API test classes (no browser). Mirrors BaseTest's
 * role for UI tests: one place that wires up ConfigReader + the client
 * (ApiClient here, DriverFactory there) so individual test classes don't
 * each duplicate setup.
 *
 * ConfigReader.reset() runs first for the same reason BaseTest/MobileBaseTest
 * call it — picks up whatever -Dsite was passed for *this* run rather than
 * a value cached from a previous test class in the same JVM.
 */
@Listeners({TestListener.class}) // same listener BaseTest uses: produces the Extent HTML report (TestListener is null-safe without a browser)
public abstract class BaseApiTest {

    private final CleanupRegistry classCleanup = new CleanupRegistry("api-class");

    @BeforeClass(alwaysRun = true)
    public void setUpApiClient() {
        ConfigReader.reset();
        ApiClient.configure();
    }

    /** Names the Extent report folder after the suite (as BaseTest does), instead of the generic "suite". */
    @BeforeMethod(alwaysRun = true)
    public void registerSuiteNameForReports(ITestContext context) {
        ExtentManager.setActiveSuiteName(context.getSuite().getName());
    }

    /**
     * Registers an undo to run once after every method in the class — the API-test counterpart of
     * {@code BaseTest.cleanupAfterClass}. Typical use is a safety net for data the test chain is
     * supposed to delete itself: if an early step fails, the step that would have deleted the
     * account is skipped or aborted, and this still removes it. See {@link CleanupRegistry}.
     */
    protected void cleanupAfterClass(String description, Runnable undo) {
        classCleanup.add(description, undo);
    }

    @AfterClass(alwaysRun = true)
    public void runApiClassCleanup() {
        classCleanup.runAll();
    }
}
