package com.automation.mobile.core;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.sites.listeners.TestListener;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

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
    public void setUp() {
        String currentSite = ConfigReader.getActiveSite();
        String requestedSite = System.getProperty("site", "mobile");
        if (currentSite == null || !currentSite.equals(requestedSite)) {
            ConfigReader.reset();
        }
        driver.set(AppiumDriverFactory.createDriver());
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (getDriver() != null) {
            try {
                getDriver().quit();
            } catch (Exception ignored) {
                // session already gone (app crash, server restart) — still clean up below
            } finally {
                driver.remove();
            }
        }
    }

    @Override
    public RemoteWebDriver getDriver() {
        return driver.get();
    }
}
