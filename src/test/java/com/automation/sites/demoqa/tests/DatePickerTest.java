package com.automation.sites.demoqa.tests;

import java.util.logging.Logger;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.DatePickerPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DatePickerTest extends BaseTest {

    private static final Logger logger = Logger.getLogger(DatePickerTest.class.getName());

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Date Picker - Select Specific Date")
    public void verifyDateSelection() {
        DatePickerPage page = new DatePickerPage(getDriver());

        page.navigateToDatePicker();
        page.selectDate("May", "1999", "15");

        String selected = page.getSelectedDate();
        logger.info("Selected date: " + selected);

        Assert.assertTrue(
            selected.contains("05/15/1999"),
            "Date should be 05/15/1999. Got: " + selected
        );
    }

    @Test(priority = 2,
        groups = {"smoke", "regression"},
        description = "Date Picker - Select Specific Date and Time")
    public void verifyDateTimeSelection() {
        DatePickerPage page = new DatePickerPage(getDriver());

        page.navigateToDatePicker();
        page.selectDateTime("May", "1999", "15", "10:30 PM");

        String selected = page.getSelectedDateTime();
        logger.info("Selected date-time: " + selected);

        // Verify that the date and time components are in the result
        Assert.assertTrue(
            selected.toLowerCase().contains("may") || selected.contains("05") || selected.contains("1999"),
            "Date portion should contain May, 05, or 1999. Got: " + selected
        );
        Assert.assertTrue(
            selected.contains("10:30") && selected.toUpperCase().contains("PM"),
            "Time portion should be 10:30 PM. Got: " + selected
        );
    }
}
