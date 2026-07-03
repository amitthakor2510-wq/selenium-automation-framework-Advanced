package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SampleTest extends BaseTest {

    @Test(groups = {"smoke"})
    public void verifyTitle() {
        String actualTitle = getDriver().getTitle();
        System.out.println("Page Title: " + actualTitle);
        Assert.assertEquals(actualTitle, "demosite");
    }
}
