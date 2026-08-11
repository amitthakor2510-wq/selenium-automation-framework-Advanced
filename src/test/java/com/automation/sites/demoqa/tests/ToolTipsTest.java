package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.ToolTipsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ToolTipsTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(ToolTipsTest.class);

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Tool Tips - Button Shows Tooltip On Hover")
    public void verifyButtonTooltip() {
        ToolTipsPage page = new ToolTipsPage(getDriver());

        page.navigateToToolTips();
        String tooltip = page.getButtonTooltipText();

        logger.info("Button tooltip: " + tooltip);
        Assert.assertEquals(tooltip, "You hovered over the Button",
            "Button tooltip text mismatch");
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Tool Tips - Text Field Shows Tooltip On Hover")
    public void verifyTextFieldTooltip() {
        ToolTipsPage page = new ToolTipsPage(getDriver());

        page.navigateToToolTips();
        String tooltip = page.getTextFieldTooltipText();

        logger.info("Text field tooltip: " + tooltip);
        Assert.assertEquals(tooltip, "You hovered over the text field",
            "Text field tooltip text mismatch");
    }
}
