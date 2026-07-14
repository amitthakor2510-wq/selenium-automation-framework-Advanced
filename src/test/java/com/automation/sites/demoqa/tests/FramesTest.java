package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.FramesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FramesTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Frames - Read Text From Frame 1")
    public void verifyFrame1Text() {
        FramesPage page = new FramesPage(getDriver());

        page.navigateToFrames();
        String text = page.getFrame1Text();

        Assert.assertEquals(text, "This is a sample page");
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Frames - Read Text From Frame 2")
    public void verifyFrame2Text() {
        FramesPage page = new FramesPage(getDriver());

        page.navigateToFrames();
        String text = page.getFrame2Text();

        Assert.assertEquals(text, "This is a sample page");
    }
}