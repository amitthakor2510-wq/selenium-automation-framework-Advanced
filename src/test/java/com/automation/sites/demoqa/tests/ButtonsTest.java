package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.ButtonsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ButtonsTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Buttons - Verify Double Click")
    public void verifyDoubleClick() {
        ButtonsPage page = new ButtonsPage(getDriver());

        page.navigateToButtons();
        page.performDoubleClick();

        Assert.assertEquals(page.getDoubleClickMessage(), "You have done a double click");
    }

    @Test(priority = 2, groups = {"smoke", "regression"},
            description = "Buttons - Verify Right Click")
    public void verifyRightClick() {
        ButtonsPage page = new ButtonsPage(getDriver());

        page.navigateToButtons();
        page.performRightClick();

        Assert.assertEquals(page.getRightClickMessage(), "You have done a right click");
    }

    @Test(priority = 3, groups = {"smoke", "regression"},
            description = "Buttons - Verify Dynamic Click")
    public void verifyDynamicClick() {
        ButtonsPage page = new ButtonsPage(getDriver());

        page.navigateToButtons();
        page.performDynamicClick();

        Assert.assertEquals(page.getDynamicClickMessage(), "You have done a dynamic click");
    }
}