package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.DynamicPropertiesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DynamicPropertiesTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void verifyButtonEnablesAfterDelay() {
        DynamicPropertiesPage page = new DynamicPropertiesPage(getDriver());

        page.navigateToDynamicProperties();

        Assert.assertTrue(page.isEnableAfterButtonEnabled(),
                "Button should become enabled after 5 seconds");
    }

    @Test(priority = 2, groups = {"regression"})
    public void verifyButtonColorChangesAfterDelay() {
        DynamicPropertiesPage page = new DynamicPropertiesPage(getDriver());

        page.navigateToDynamicProperties();

        Assert.assertTrue(page.hasColorChanged(),
                "Button color should change to red after 5 seconds");

        Assert.assertTrue(page.getColorButtonClass().contains("text-danger"),
                "Button class should contain text-danger");
    }

    @Test(priority = 3, groups = {"regression"})
    public void verifyButtonAppearsAfterDelay() {
        DynamicPropertiesPage page = new DynamicPropertiesPage(getDriver());

        page.navigateToDynamicProperties();

        Assert.assertTrue(page.isVisibleAfterButtonDisplayed(),
                "Button should appear after 5 seconds");
    }
}