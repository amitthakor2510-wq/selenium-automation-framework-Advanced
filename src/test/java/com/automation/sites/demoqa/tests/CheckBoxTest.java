package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.CheckBoxPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CheckBoxTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Check Box - Expand Tree and Select Desktop")
    public void verifyDesktopCheckboxSelection() {
        CheckBoxPage page = new CheckBoxPage(getDriver());

        page.navigateToCheckBox();
        page.expandTree();
        page.selectDesktop();

        Assert.assertTrue(page.isResultDisplayed());
    }
}
