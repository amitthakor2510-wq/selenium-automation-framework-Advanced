package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.AutoCompletePage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AutoCompleteTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Auto Complete - Multi Select Accepts Multiple Colors")
    public void verifyMultiColorInput() {
        AutoCompletePage page = new AutoCompletePage(getDriver());

        page.navigateToAutoComplete();
        page.typeMultiColor("Red");
        page.typeMultiColor("Blue");

        Assert.assertEquals(
                page.getMultiSelectedCount(), 2,
                "Should have 2 colors selected"
        );
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Auto Complete - Single Select Shows Selected Color")
    public void verifySingleColorInput() {
        AutoCompletePage page = new AutoCompletePage(getDriver());

        page.navigateToAutoComplete();
        page.typeSingleColor("Green");

        Assert.assertTrue(
                page.getSingleSelectedValue().contains("Green"),
                "Single input should show Green. Got: "
                        + page.getSingleSelectedValue()
        );
    }
}
