package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.WebTablesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WebTablesCRUDTest extends BaseTest {

    @Test(groups = {"smoke", "regression"},
        description = "Web Tables - Full CRUD Lifecycle: Open, Add, Search, Edit, Delete")
    public void verifyFullCRUDOperation() {
        WebTablesPage page = new WebTablesPage(getDriver());

        // OPEN
        page.openPage();

        // ADD
        page.clickAddButton();
        page.addRecord("Amit", "Thakor", "amit@test.com",
            "30", "50000", "QA");

        // SEARCH
        page.searchRecord("Amit");
        Assert.assertTrue(page.isRecordPresent("Amit"),
            "Record 'Amit' should be present after adding");

        // EDIT
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
