package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class RegistrationPage extends BasePage {

    // IDs verified from compiled RegistrationPage.class:
    private final By firstNameInput  = By.id("firstname");
    private final By lastNameInput   = By.id("lastname");
    private final By userNameInput   = By.id("userName");
    private final By emailInput      = By.id("email");
    private final By passwordInput   = By.id("password");
    private final By registerButton  = By.id("register");
    private final By backToLoginLink = By.id("gotologin");

    // Captured from the native JS alert shown after clicking Register.
    // This is the ONLY reliable success/failure signal DemoQA gives us here —
    // the page does not redirect and does not render a success/error element.
    private String lastAlertText = "";

    public RegistrationPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToRegistration() {
        navigateTo("/register");
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
        System.out.println("  Navigated to registration page");
    }

    private void fillField(By locator, String value, String fieldName) {
        WebElement el = wait.until(
                ExpectedConditions.visibilityOfElementLocated(locator));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        js.executeScript("arguments[0].click();", el);

        el.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        el.sendKeys(Keys.DELETE);

        HumanActions.typeHumanLike(el, value);

        String actual = el.getAttribute("value");
        System.out.println("  " + fieldName + ": typed='" + value
                + "' actual='" + actual + "'");
    }

    public void registerUser(String firstName, String lastName,
                             String userName, String email, String password) {
        System.out.println("  Filling registration form...");

        fillField(firstNameInput, firstName,  "First name");
        fillField(lastNameInput,  lastName,   "Last name");
        fillField(userNameInput,  userName,   "Username");
        fillField(emailInput,     email,      "Email");
        fillField(passwordInput,  password,   "Password");
        wait.until(ExpectedConditions.elementToBeClickable(registerButton));


        WebElement btn = wait.until(
                ExpectedConditions.presenceOfElementLocated(registerButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);
        System.out.println("  Register clicked");

        lastAlertText = "";
        try {
            new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(ExpectedConditions.alertIsPresent());
            lastAlertText = driver.switchTo().alert().getText();
            System.out.println("  Alert: " + lastAlertText);
            driver.switchTo().alert().accept();
        } catch (TimeoutException e) {
            System.out.println("  No alert appeared");
        }

        System.out.println("  URL after register: " + driver.getCurrentUrl());
    }

    /**
     * The alert text is the primary success signal on DemoQA's Book Store
     * register page — it does NOT redirect to /login and does NOT render any
     * success/error text in the DOM on success. URL/text checks are kept only
     * as a fallback in case DemoQA's behavior changes.
     */
    public boolean isRegistrationSuccessful() {
        String alert = lastAlertText.toLowerCase();

        if (alert.contains("success")) {
            System.out.println("  Alert confirmed success: " + lastAlertText);
            return true;
        }
        if (alert.contains("already exist") || alert.contains("user exists")) {
            System.out.println("  Registration failed — user already exists: " + lastAlertText);
            return false;
        }

        // Fallback heuristics (kept in case DemoQA ever changes to redirect-based flow)
        String url = driver.getCurrentUrl();
        if (url.contains("/login") || url.contains("/profile")) {
            System.out.println("  Redirected to " + url + " ✓");
            return true;
        }
        By success = By.xpath(
                "//*[contains(text(),'registered') or contains(text(),'success')]");
        if (!driver.findElements(success).isEmpty()) {
            System.out.println("  Success element visible ✓");
            return true;
        }

        System.out.println("  Registration uncertain. Alert='" + lastAlertText + "' URL=" + url);
        return false;
    }

    public void clickBackToLogin() {
        try {
            WebElement link = wait.until(
                    ExpectedConditions.elementToBeClickable(backToLoginLink));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", link);
            js.executeScript("arguments[0].click();", link);
            wait.until(ExpectedConditions.urlContains("/login"));
            System.out.println("  On /login ✓");
        } catch (Exception e) {
            System.out.println("  Navigating to /login directly");
            navigateTo("/login");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("userName")));
        }
    }
}