package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.AlertsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AlertsTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Alerts - Simple Alert Shows Correct Text")
    public void verifySimpleAlert() {
        AlertsPage page = new AlertsPage(getDriver());

        page.navigateToAlerts();
        String text = page.clickAlertAndGetText();

        Assert.assertEquals(text, "You clicked a button");
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Alerts - Timer Alert Appears After 5 Seconds")
    public void verifyTimerAlert() {
        AlertsPage page = new AlertsPage(getDriver());

        page.navigateToAlerts();
        String text = page.clickTimerAlertAndGetText();

        Assert.assertEquals(text, "This alert appeared after 5 seconds");
    }

    @Test(priority = 3,
            groups = {"regression"},
            description = "Alerts - Confirm Alert Accept Shows Ok")
    public void verifyConfirmAlertAccept() {
        AlertsPage page = new AlertsPage(getDriver());

        page.navigateToAlerts();
        String result = page.clickConfirmAndAccept();

        Assert.assertTrue(
                result.contains("Ok"),
                "Result should contain Ok. Got: " + result
        );
    }

    @Test(priority = 4,
            groups = {"regression"},
            description = "Alerts - Confirm Alert Dismiss Shows Cancel")
    public void verifyConfirmAlertDismiss() {
        AlertsPage page = new AlertsPage(getDriver());

        page.navigateToAlerts();
        String result = page.clickConfirmAndDismiss();

        Assert.assertTrue(
                result.contains("Cancel"),
                "Result should contain Cancel. Got: " + result
        );
    }

    @Test(priority = 5,
            groups = {"regression"},
            description = "Alerts - Prompt Alert Accepts Typed Text")
    public void verifyPromptAlert() {
        AlertsPage page = new AlertsPage(getDriver());

        page.navigateToAlerts();
        String result = page.clickPromptAndEnterText("Amit");

        Assert.assertTrue(
                result.contains("Amit"),
                "Result should contain entered text. Got: " + result
        );
    }
}