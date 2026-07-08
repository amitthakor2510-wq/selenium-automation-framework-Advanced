package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.ProgressBarPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProgressBarTest extends BaseTest {

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
}