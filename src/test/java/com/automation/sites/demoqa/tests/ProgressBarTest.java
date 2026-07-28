package com.automation.sites.demoqa.tests;

import java.util.logging.Logger;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.ProgressBarPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProgressBarTest extends BaseTest {

    private static final Logger logger = Logger.getLogger(ProgressBarTest.class.getName());

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Progress Bar - Starts At 0")
    public void verifyProgressBarStartsAt0() {
        ProgressBarPage page = new ProgressBarPage(getDriver());

        page.navigateToProgressBar();

        Assert.assertEquals(
            page.getProgressValue(), "0",
            "Progress bar should start at 0"
        );
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Progress Bar - Reaches 100 After Start")
    public void verifyProgressBarCompletes() {
        ProgressBarPage page = new ProgressBarPage(getDriver());

        page.navigateToProgressBar();
        page.waitForCompletion();

        Assert.assertEquals(
            page.getProgressValue(), "100",
            "Progress bar should reach 100"
        );
    }

    @Test(priority = 3,
        groups = {"regression"},
        description = "Progress Bar - Resets To 0 After Reset")
    public void verifyProgressBarReset() {
        ProgressBarPage page = new ProgressBarPage(getDriver());

        page.navigateToProgressBar();
        page.waitForCompletion();
        page.clickReset();

        Assert.assertEquals(
            page.getProgressValue(), "0",
            "Progress bar should reset to 0"
        );
    }

    @Test(priority = 4,
        groups = {"regression"},
        description = "Progress Bar - Stop At Specific Value")
    public void verifyProgressBarStopsAtValue() {
        ProgressBarPage page = new ProgressBarPage(getDriver());

        page.navigateToProgressBar();
        page.startAndStopAtValue(68);

        String value = page.getProgressValue();
        logger.info("Progress stopped at: " + value);

        int actual = Integer.parseInt(value);
        Assert.assertTrue(
            actual >= 65 && actual <= 71,
            "Progress bar should stop near 68%. Got: " + value
        );
    }
}
