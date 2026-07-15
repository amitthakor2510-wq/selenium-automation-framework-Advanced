package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.WebTablesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WebTablesCRUDTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke","regression"},
            description = "Web Tables - Full CRUD: Add, Edit, Delete a record")
    public void verifyFullCRUDOperation() {
        WebTablesPage page = new WebTablesPage(getDriver());

        // Navigate
        page.openPage();

        // ADD
        page.clickAddButton();
        page.addRecord("Amit", "Thakor", "amit@test.com",
                "30", "50000", "QA");

        page.searchRecord("Amit");
        Assert.assertTrue(page.isRecordPresent("Amit"),
                "Record 'Amit' should be present after adding");

        // UPDATE
        page.updateRecord("Amit", "Automation");
        page.searchRecord("Amit");
        Assert.assertTrue(page.isRecordPresent("Automation"),
                "Department should be updated to 'Automation'");

        // DELETE
        page.deleteRecord("Amit");
        page.searchRecord("Amit");
        Assert.assertFalse(page.isRecordPresent("Amit"),
                "Record 'Amit' should be gone after deleting");
    }
}