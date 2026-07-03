package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.RadioButtonPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RadioButtonTest extends BaseTest {

    @Test(groups = {"regression"})
    public void verifyRadioButton() {
        RadioButtonPage page = new RadioButtonPage(getDriver());

        page.navigateToRadioButton();
        page.selectYes();

        Assert.assertEquals(page.getResultText(), "Yes");
    }
}
