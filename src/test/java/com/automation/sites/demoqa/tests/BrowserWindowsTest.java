package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.BrowserWindowsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BrowserWindowsTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Browser Windows - New Tab Opens With Correct Text")
    public void verifyNewTab() {
        BrowserWindowsPage page = new BrowserWindowsPage(getDriver());

        page.navigateToBrowserWindows();
        String text = page.clickNewTabAndGetText();

        Assert.assertTrue(
                text.contains("This is a sample page"),
                "New tab should contain sample page text. Got: " + text
        );
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Browser Windows - New Window Opens With Correct Text")
    public void verifyNewWindow() {
        BrowserWindowsPage page = new BrowserWindowsPage(getDriver());

        page.navigateToBrowserWindows();
        String text = page.clickNewWindowAndGetText();

        Assert.assertTrue(
                text.contains("This is a sample page"),
                "New window should contain sample page text. Got: " + text
        );
    }

    @Test(priority = 3,
            groups = {"regression"},
            description = "Browser Windows - New Window Message Shows Correctly")
    public void verifyNewWindowMessage() {
        BrowserWindowsPage page = new BrowserWindowsPage(getDriver());

        page.navigateToBrowserWindows();
        String text = page.clickNewWindowMessageAndGetText();

        Assert.assertFalse(
                text.isEmpty(),
                "New window message should not be empty"
        );
    }
}