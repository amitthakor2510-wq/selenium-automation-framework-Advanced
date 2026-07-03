package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.WebTablesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WebTablesCRUDTest extends BaseTest {

    @Test(groups = {"regression"})
    public void verifyFullCRUDOperation() {
        WebTablesPage page = new WebTablesPage(getDriver());

        page.openPage();

        // ADD
        page.clickAddButton();
        page.addRecord("Amit", "Thakor", "amit@test.com",
                "30", "50000", "QA");

        page.searchRecord("Amit");
        Assert.assertTrue(page.isRecordPresent("Amit"));

        // UPDATE
        page.updateRecord("Amit", "Automation");
        page.searchRecord("Amit");
        Assert.assertTrue(page.isRecordPresent("Automation"));

        // DELETE
        page.deleteRecord("Amit");
        page.searchRecord("Amit");
        Assert.assertFalse(page.isRecordPresent("Amit"));
    }
}
