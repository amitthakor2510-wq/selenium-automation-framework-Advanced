package com.automation.sites.saucedemo.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.saucedemo.pages.LoginPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LoginTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "SauceDemo - Verify Successful Login")
    public void verifyLogin() {
        LoginPage page = new LoginPage(getDriver());
        page.navigateToLogin();
        page.login("standard_user", "secret_sauce");

        Assert.assertTrue(getDriver().getCurrentUrl().contains("inventory"),
                "Login failed - not redirected to inventory page");
    }
}