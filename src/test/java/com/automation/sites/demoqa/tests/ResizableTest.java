package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.ResizablePage;
import org.openqa.selenium.Dimension;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ResizableTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(ResizableTest.class);

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Resizable - Box Has Default Size 200x200")
    public void verifyDefaultSize() {
        ResizablePage page = new ResizablePage(getDriver());
        page.navigateToResizable();

        Dimension size = page.getBoxSize();
        logger.info("Default size: " + size.width + "x" + size.height);

        Assert.assertEquals(size.width, 200, "Default width should be 200px");
        Assert.assertEquals(size.height, 200, "Default height should be 200px");
    }

    @Test(priority = 2, groups = {"regression"},
        description = "Resizable - Dragging Handle Increases Box Size")
    public void verifyResizeIncrease() {
        ResizablePage page = new ResizablePage(getDriver());
        page.navigateToResizable();

        Dimension before = page.getBoxSize();
        logger.info("Before: " + before.width + "x" + before.height);

        page.resizeBy(100, 50);

        Dimension after = page.getBoxSize();
        logger.info("After:  " + after.width + "x" + after.height);

        Assert.assertTrue(after.width > before.width, "Width should increase");
        Assert.assertTrue(after.height > before.height, "Height should increase");
    }

    @Test(priority = 3, groups = {"regression"},
        description = "Resizable - Box Cannot Exceed Max Size 500x300")
    public void verifyMaxSizeConstraint() {
        ResizablePage page = new ResizablePage(getDriver());
        page.navigateToResizable();

        page.resizeBy(500, 500);

        Dimension size = page.getBoxSize();
        logger.info("After large drag: " + size.width + "x" + size.height);

        Assert.assertTrue(size.width <= 500, "Width should not exceed 500px");
        Assert.assertTrue(size.height <= 300, "Height should not exceed 300px");
    }
}
