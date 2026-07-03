package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.BrokenLinksImagesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BrokenLinksImagesTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void verifyValidImageIsLoaded() {
        BrokenLinksImagesPage page = new BrokenLinksImagesPage(getDriver());

        page.navigateToBrokenLinksImages();

        Assert.assertTrue(page.isValidImageLoaded(),
                "Valid image should load successfully");
    }

    @Test(priority = 2, groups = {"regression"})
    public void verifyBrokenImageIsNotLoaded() {
        BrokenLinksImagesPage page = new BrokenLinksImagesPage(getDriver());

        page.navigateToBrokenLinksImages();

        Assert.assertFalse(page.isBrokenImageLoaded(),
                "Broken image should NOT load - naturalWidth should be 0");
    }

    @Test(priority = 3, groups = {"regression"})
    public void verifyValidLinkNavigatesCorrectly() {
        BrokenLinksImagesPage page = new BrokenLinksImagesPage(getDriver());

        page.navigateToBrokenLinksImages();
        String url = page.clickValidLinkAndGetUrl();

        Assert.assertTrue(url.contains("demoqa.com"),
                "Valid link should navigate to demoqa.com");
    }

    @Test(priority = 4, groups = {"regression"})
    public void verifyBrokenLinkResponse() {
        BrokenLinksImagesPage page = new BrokenLinksImagesPage(getDriver());

        page.navigateToBrokenLinksImages();
        String url = page.clickBrokenLinkAndGetUrl();

        Assert.assertTrue(url.contains("statusCode") || url.contains("500"),
                "Broken link should lead to a 500 error page");
    }
}