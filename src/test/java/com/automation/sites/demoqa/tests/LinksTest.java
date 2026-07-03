package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.LinksPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LinksTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void verifyHomeLinkOpensNewTab() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        String url = page.clickHomeLinkAndGetNewTabUrl();
        Assert.assertTrue(url.contains("demoqa.com"));
    }

    @Test(priority = 2, groups = {"regression"})
    public void verifyDynamicHomeLinkOpensNewTab() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        String url = page.clickDynamicHomeLinkAndGetNewTabUrl();
        Assert.assertTrue(url.contains("demoqa.com"));
    }

    @Test(priority = 3, groups = {"regression"})
    public void verifyCreatedLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickCreatedLink().contains("201"));
    }

    @Test(priority = 4, groups = {"regression"})
    public void verifyNoContentLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickNoContentLink().contains("204"));
    }

    @Test(priority = 5, groups = {"regression"})
    public void verifyBadRequestLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickBadRequestLink().contains("400"));
    }

    @Test(priority = 6, groups = {"regression"})
    public void verifyUnauthorizedLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickUnauthorizedLink().contains("401"));
    }

    @Test(priority = 7, groups = {"regression"})
    public void verifyForbiddenLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickForbiddenLink().contains("403"));
    }

    @Test(priority = 8, groups = {"regression"})
    public void verifyNotFoundLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickNotFoundLink().contains("404"));
    }
}