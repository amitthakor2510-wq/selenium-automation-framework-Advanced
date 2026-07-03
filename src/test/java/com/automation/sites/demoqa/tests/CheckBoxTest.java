package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.CheckBoxPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CheckBoxTest extends BaseTest {

    @Test(groups = {"regression"})
    public void verifyDesktopCheckboxSelection() {
        CheckBoxPage page = new CheckBoxPage(getDriver());

        page.navigateToCheckBox();
        page.expandTree();
        page.selectDesktop();

        Assert.assertTrue(page.isResultDisplayed());
    }
}
