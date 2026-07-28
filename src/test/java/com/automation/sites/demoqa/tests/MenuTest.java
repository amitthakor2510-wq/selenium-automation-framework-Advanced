package com.automation.sites.demoqa.tests;

import java.util.logging.Logger;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.MenuPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MenuTest extends BaseTest {

    private static final Logger logger = Logger.getLogger(MenuTest.class.getName());

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Menu - Main Item 1 Is Visible")
    public void verifyMainItem1Visible() {
        MenuPage page = new MenuPage(getDriver());

        page.navigateToMenu();
        String text = page.getMainItem1Text();

        logger.info("Main item 1 text: " + text);
        Assert.assertEquals(text, "Main Item 1");
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Menu - Sub Item Appears On Hover")
    public void verifySubItemOnHover() {
        MenuPage page = new MenuPage(getDriver());

        page.navigateToMenu();
        page.hoverMainItem2();

        Assert.assertTrue(
            page.isSubItemVisible(),
            "Sub Item should appear after hovering Main Item 2"
        );
    }

    @Test(priority = 3,
        groups = {"regression"},
        description = "Menu - Sub Sub Item Appears On Nested Hover")
    public void verifySubSubItemOnHover() {
        MenuPage page = new MenuPage(getDriver());

        page.navigateToMenu();
        page.hoverToSubSubList();

        Assert.assertTrue(
            page.isSubSubItem1Visible(),
            "Sub Sub Item 1 should appear after nested hover"
        );
    }
}
