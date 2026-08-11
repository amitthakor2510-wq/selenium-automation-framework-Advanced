package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.sites.core.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SampleTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(SampleTest.class);

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
