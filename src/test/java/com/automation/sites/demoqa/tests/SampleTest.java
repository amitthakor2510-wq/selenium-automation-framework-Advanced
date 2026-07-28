package com.automation.sites.demoqa.tests;

import java.util.logging.Logger;

import com.automation.sites.core.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SampleTest extends BaseTest {

    private static final Logger logger = Logger.getLogger(SampleTest.class.getName());

    @Test(priority = 1, groups = {"smoke"},
        description = "Home Page - Verify Page Title")
    public void verifyTitle() {
        String actualTitle = getDriver().getTitle();
        logger.info("Page Title: " + actualTitle);
        Assert.assertTrue(
            actualTitle.contains("demosite"),
            "Unexpected page title: " + actualTitle
        );
    }
}
