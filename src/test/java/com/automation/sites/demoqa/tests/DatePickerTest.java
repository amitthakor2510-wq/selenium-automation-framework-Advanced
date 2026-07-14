package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.DatePickerPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DatePickerTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Date Picker - Select Specific Date")
    public void verifyDateSelection() {
        DatePickerPage page = new DatePickerPage(getDriver());

        page.navigateToDatePicker();
        page.selectDate("May", "1999", "15");

        String selected = page.getSelectedDate();
        System.out.println("Selected date: " + selected);

        Assert.assertTrue(
                selected.contains("05/15/1999"),
                "Date should be 05/15/1999. Got: " + selected
        );
    }
}