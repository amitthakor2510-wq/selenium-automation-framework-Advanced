package com.automation.core.base;

import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

/**
 * Every site's test classes extend this. Site is selected via
 * -Dsite=<siteName> (see ConfigReader); the driver navigates to
 * whatever "url" resolves to for that site's config file.
 */
public class BaseTest {

    // Thread-safe driver for parallel execution
    private static final ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        WebDriver webDriver = DriverFactory.createDriver();
        driver.set(webDriver);

        getDriver().get(ConfigReader.get("url"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (getDriver() != null) {
            getDriver().quit();
            driver.remove(); // Important for memory cleanup
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
