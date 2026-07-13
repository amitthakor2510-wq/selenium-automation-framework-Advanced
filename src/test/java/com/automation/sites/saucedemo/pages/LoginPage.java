package com.automation.sites.saucedemo.pages;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.time.Duration;

public class LoginPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    private final By usernameField = By.id("user-name");
    private final By passwordField = By.id("password");
    private final By loginButton   = By.id("login-button");

    public LoginPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(
                ConfigReader.getInt("timeout", 10)));
    }

    public void navigateToLogin() {
        driver.get(ConfigReader.get("url"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
    }

    public void login(String username, String password) {
        HumanActions.type(driver, usernameField, username);
        HumanActions.type(driver, passwordField, password);
        HumanActions.click(driver, loginButton);
    }
}