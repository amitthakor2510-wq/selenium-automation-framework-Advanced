package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.TabsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TabsTest extends BaseTest {

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Tabs - What Tab Is Active By Default")
    public void verifyWhatTabContent() {
        TabsPage page = new TabsPage(getDriver());

        page.navigateToTabs();

        Assert.assertFalse(
            page.getWhatTabContent().isEmpty(),
            "What tab should have content"
        );
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Tabs - Origin Tab Shows Content On Click")
    public void verifyOriginTabContent() {
        TabsPage page = new TabsPage(getDriver());

        page.navigateToTabs();

        Assert.assertFalse(
            page.getOriginTabContent().isEmpty(),
            "Origin tab should have content"
        );
    }

    @Test(priority = 3,
        groups = {"regression"},
        description = "Tabs - Use Tab Shows Content On Click")
    public void verifyUseTabContent() {
        TabsPage page = new TabsPage(getDriver());

        page.navigateToTabs();

        Assert.assertFalse(
            page.getUseTabContent().isEmpty(),
            "Use tab should have content"
        );
    }
}
