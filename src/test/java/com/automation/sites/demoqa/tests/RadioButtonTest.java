package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.RadioButtonPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RadioButtonTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Radio Button - Select Yes Option")
    public void verifyRadioButton() {
        RadioButtonPage page = new RadioButtonPage(getDriver());

        page.navigateToRadioButton();
        page.selectYes();

        Assert.assertEquals(page.getResultText(), "Yes");
    }
}
