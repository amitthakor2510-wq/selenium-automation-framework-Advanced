package com.automation.sites.saucedemo.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class LoginPage extends BasePage {

    private final By usernameField = By.id("user-name");
    private final By passwordField = By.id("password");
    private final By loginButton   = By.id("login-button");

    public LoginPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToLogin() {
        navigateTo("");
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
    }

    public void login(String username, String password) {
        HumanActions.type(driver, usernameField, username);
        HumanActions.type(driver, passwordField, password);
        HumanActions.click(driver, loginButton);
    }
}