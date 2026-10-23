package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.SelectablePage;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class SelectableTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(SelectableTest.class);

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Selectable - Single Click Selects One Item")
    public void verifySingleSelection() {
        SelectablePage page = new SelectablePage(getDriver());
        page.navigateToSelectable();
        page.clickListItem(0);

        int active = page.getActiveListItemCount();
        logger.info("Active items: " + active);

        Assert.assertEquals(active, 1, "Only 1 item should be selected");
    }

    @Test(priority = 2, groups = {"regression"},
        description = "Selectable - Ctrl+Click Selects Multiple Items")
    public void verifyMultiSelection() {
        SelectablePage page = new SelectablePage(getDriver());
        page.navigateToSelectable();
        page.ctrlClickListItems(0, 1, 2);

        int active = page.getActiveListItemCount();
        List<String> texts = page.getActiveListItemTexts();
        logger.info("Active items after Ctrl+Click: " + texts);

        Assert.assertEquals(active, 3, "3 items should be selected after Ctrl+Click");
    }

    @Test(priority = 3, groups = {"regression"},
        description = "Selectable - Grid Single Click Selects One Item")
    public void verifyGridSingleSelection() {
        SelectablePage page = new SelectablePage(getDriver());
        page.navigateToSelectable();
        page.clickGridTab();
        page.clickGridItem(0);

        int active = page.getActiveGridItemCount();
        logger.info("Active grid items: " + active);

        Assert.assertEquals(active, 1, "Only 1 grid item should be selected");
    }
}
