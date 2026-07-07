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
            description = "Browser Windows - New Window Message Opens And Closes")
    public void verifyNewWindowMessage() {
        BrowserWindowsPage page = new BrowserWindowsPage(getDriver());

        page.navigateToBrowserWindows();

        // This window opens and closes itself very quickly
        // The test verifies it opens without throwing an exception
        String text = page.clickNewWindowMessageAndGetText();

        // Just verify we got back to the original window without crashing
        Assert.assertNotNull(text,
                "Should have handled the message window without error"
        );

        // Verify we are back on the correct page
        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("demoqa"),
                "Should be back on demoqa after message window closes"
        );
    }
}