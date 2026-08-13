package com.automation.mobile.core;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.report.AllureEnvironmentWriter;
import com.automation.core.report.ExtentManager;
import com.automation.sites.listeners.TestListener;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.MDC;
import org.testng.ITestContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

import java.lang.reflect.Method;

/**
 * Mobile counterpart to com.automation.sites.core.BaseTest — every Appium
 * screen-test class should extend this instead of BaseTest. Same
 * ThreadLocal-driver / setUp-tearDown shape as the web base class, just
 * pointed at AppiumDriverFactory instead of DriverFactory.
 *
 * Config is picked up the normal ConfigReader way (config/global.properties
 * + config/{site}.properties + -D overrides) by running with
 * -Dsite=mobile, once you've copied
 * src/test/resources/config/mobile.properties.example to
 * src/test/resources/config/mobile.properties and filled in real values.
 * This is the "wire a mobile pseudo-site into ConfigReader" approach the
 * .example file's header comment mentions — no ConfigReader code changes
 * needed, since it already loads config/<site>.properties for whatever
 * -Dsite you pass.
 *
 * TestListener is reused as-is for screenshot-on-failure + Allure/Extent
 * hookup — it looks up the driver via DriverProvider.getDriver(), which
 * this class implements the same way BaseTest does, so failure screenshots
 * / page-source dumps work unmodified for a RemoteWebDriver-backed
 * AndroidDriver/IOSDriver (both implement TakesScreenshot).
 */
@Listeners({TestListener.class})
public class MobileBaseTest implements DriverProvider {

    protected static final ThreadLocal<RemoteWebDriver> driver = new ThreadLocal<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp(Method testMethod, ITestContext context) {
        // Same reasoning as BaseTest.setUp(Method): tags AppiumDriverFactory's
        // driver-creation logging below with the upcoming test's name via
        // TestNG's @BeforeMethod(Method) injection, closing the same
        // untagged-setup-log gap for the mobile module.
        MDC.put("test", testMethod.getDeclaringClass().getSimpleName() + "#" + testMethod.getName());

        // Same reasoning as BaseTest.setUp(): TestListener.onStart() (a
        // <test>-level ITestListener callback) fires before TestNG has
        // discovered this class's @Listeners annotation, so it never
        // actually runs writeOnce() for a class registered this way — the
        // mobile Allure report's Environment/Categories widgets would stay
        // empty without this redundant call. writeOnce()'s internal guard
        // keeps this cheap even though it now runs before every test method.
        AllureEnvironmentWriter.writeOnce();

        // BUG FIX: same dead-listener root cause, same fix, as
        // BaseTest.setUp()'s identical block — see that comment for the
        // full explanation. ExtentManager.setActiveSuiteName() was never
        // called anywhere in the codebase (mobile or web), so every
        // mobile-smoke/mobile-regression Extent report also collapsed onto
        // the same "suite"-slugged file, silently overwriting each other's
        // screenshots across back-to-back runs in one JVM.
        ExtentManager.setActiveSuiteName(context.getSuite().getName());

        String currentSite = ConfigReader.getActiveSite();
        String requestedSite = System.getProperty("site", "mobile");
        if (currentSite == null || !currentSite.equals(requestedSite)) {
            ConfigReader.reset();
        }
        driver.set(AppiumDriverFactory.createDriver());
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        // Same reasoning as BaseTest.tearDown(): TestListener.afterInvocation()
        // no longer clears the MDC "test" tag, so driver.quit() below stays
        // tagged; this outer finally is what actually clears it once teardown
        // logging is done, guaranteed via alwaysRun=true.
        try {
            if (getDriver() != null) {
                try {
                    getDriver().quit();
                } catch (Exception e) {
                    // Previously swallowed silently — same fix as BaseTest.java:
                    // log it so a failed Appium session teardown is visible
                    // instead of hidden.
                    org.slf4j.LoggerFactory.getLogger(MobileBaseTest.class)
                        .warn("[MobileBaseTest] driver.quit() failed: " + e.getMessage());
                } finally {
                    driver.remove();
                }
            }
        } finally {
            MDC.remove("test");
        }
    }

    @Override
    public RemoteWebDriver getDriver() {
        return driver.get();
    }
}
