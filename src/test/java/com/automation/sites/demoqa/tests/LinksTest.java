package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.LinksPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LinksTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Links - Home Link Opens New Tab")
    public void verifyHomeLinkOpensNewTab() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        String url = page.clickHomeLinkAndGetNewTabUrl();
        Assert.assertTrue(url.contains("demoqa.com"));
    }


    @Test(priority = 2, groups = {"regression"},
        description = "Links - Dynamic Home Link Opens New Tab")
    public void verifyDynamicHomeLinkOpensNewTab() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        String url = page.clickDynamicHomeLinkAndGetNewTabUrl();
        Assert.assertTrue(url.contains("demoqa.com"));
    }

    @Test(priority = 3, groups = {"regression"},
        description = "Links - Created API Link Returns 201")
    public void verifyCreatedLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickCreatedLink().contains("201"));
    }


    @Test(priority = 4, groups = {"regression"},
        description = "Links - No Content API Link Returns 204")
    public void verifyNoContentLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickNoContentLink().contains("204"));
    }

    @Test(priority = 5, groups = {"regression"},
        description = "Links - Bad Request API Link Returns 400")
    public void verifyBadRequestLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickBadRequestLink().contains("400"));
    }

    @Test(priority = 6, groups = {"regression"},
        description = "Links - Unauthorized API Link Returns 401")
    public void verifyUnauthorizedLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickUnauthorizedLink().contains("401"));
    }

    @Test(priority = 7, groups = {"regression"},
        description = "Links - Forbidden API Link Returns 403")
    public void verifyForbiddenLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickForbiddenLink().contains("403"));
    }

    @Test(priority = 8, groups = {"regression"},
        description = "Links - Not Found API Link Returns 404")
    public void verifyNotFoundLink() {
        LinksPage page = new LinksPage(getDriver());
        page.navigateToLinks();
        Assert.assertTrue(page.clickNotFoundLink().contains("404"));
    }
}
