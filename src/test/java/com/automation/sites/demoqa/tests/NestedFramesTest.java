package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.NestedFramesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class NestedFramesTest extends BaseTest {

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Nested Frames - Read Text From Parent Frame")
    public void verifyParentFrameText() {
        NestedFramesPage page = new NestedFramesPage(getDriver());

        page.navigateToNestedFrames();
        String text = page.getParentFrameText();

        Assert.assertTrue(
            text.contains("Parent frame"),
            "Parent frame should contain 'Parent frame'. Got: " + text
        );
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Nested Frames - Read Text From Child Frame Inside Parent")
    public void verifyChildFrameText() {
        NestedFramesPage page = new NestedFramesPage(getDriver());

        page.navigateToNestedFrames();
        String text = page.getChildFrameText();

        Assert.assertTrue(
            text.contains("Child Iframe"),
            "Child frame should contain 'Child Iframe'. Got: " + text
        );
    }
}
