package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.SelectMenuPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SelectMenuTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Select Menu - Old Style Select Works")
    public void verifyOldStyleSelect() {
        SelectMenuPage page = new SelectMenuPage(getDriver());

        page.navigateToSelectMenu();
        page.selectOldStyleOption("Blue");

        Assert.assertEquals(
                page.getOldStyleSelectedValue(), "Blue",
                "Old style select should show Blue"
        );
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Select Menu - Standard Multi Select Works")
    public void verifyStandardMultiSelect() {
        SelectMenuPage page = new SelectMenuPage(getDriver());

        page.navigateToSelectMenu();
        page.selectCarOption("Volvo");

        Assert.assertEquals(
                page.getSelectedCarOption(), "Volvo",
                "Multi select should show Volvo"
        );
    }
}