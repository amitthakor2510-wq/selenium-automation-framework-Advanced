package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class RegistrationPage extends BasePage {

    private final By firstNameInput  = By.id("firstname");
    private final By lastNameInput   = By.id("lastname");
    private final By userNameInput   = By.id("userName");
    private final By emailInput      = By.id("email");
    private final By passwordInput   = By.id("password");
    private final By registerButton  = By.id("register");
    private final By backToLoginLink = By.id("gotologin");

    public RegistrationPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToRegistration() {
        navigateTo("/register");
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
        System.out.println("  Navigated to registration page");
    }

    private void fillField(By locator, String value, String fieldName) {
        WebElement el = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        js.executeScript("arguments[0].click();", el);
        el.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        el.sendKeys(Keys.DELETE);
        HumanActions.typeHumanLike(el, value);
        System.out.println("  " + fieldName + ": typed='" + value
                + "' actual='" + el.getAttribute("value") + "'");
    }

    /**
     * FIX #10: registerUser() now RETURNS the alert text instead of storing
     * it in an instance field. This removes the implicit shared state between
     * registerUser() and isRegistrationSuccessful(), making both methods
     * safe to call independently and in any order.
     *
     * @return the text from the JS alert shown after clicking Register,
     *         or an empty string if no alert appeared.
     */
    public String registerUser(String firstName, String lastName,
                               String userName, String email, String password) {
        System.out.println("  Filling registration form...");
        fillField(firstNameInput, firstName, "First name");
        fillField(lastNameInput,  lastName,  "Last name");
        fillField(userNameInput,  userName,  "Username");
        fillField(emailInput,     email,     "Email");
        fillField(passwordInput,  password,  "Password");

        wait.until(ExpectedConditions.elementToBeClickable(registerButton));
        WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(registerButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);
        System.out.println("  Register clicked");

        String alertText = "";
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.alertIsPresent());
            alertText = driver.switchTo().alert().getText();
            System.out.println("  Alert: " + alertText);
            driver.switchTo().alert().accept();
        } catch (TimeoutException e) {
            System.out.println("  No alert appeared");
        }

        System.out.println("  URL after register: " + driver.getCurrentUrl());
        return alertText;
    }

    /**
     * FIX #10: Now accepts alertText as a parameter — no implicit state dependency.
     * Call: boolean ok = page.isRegistrationSuccessful(page.registerUser(...))
     */
    public boolean isRegistrationSuccessful(String alertText) {
        String alert = alertText.toLowerCase();

        if (alert.contains("success")) {
            System.out.println("  Alert confirmed success: " + alertText);
            return true;
        }
        if (alert.contains("already exist") || alert.contains("user exists")) {
            System.out.println("  Registration failed — user already exists: " + alertText);
            return false;
        }

        // Fallback heuristics
        String url = driver.getCurrentUrl();
        if (url.contains("/login") || url.contains("/profile")) {
            System.out.println("  Redirected to " + url + " ✓");
            return true;
        }
        By success = By.xpath("//*[contains(text(),'registered') or contains(text(),'success')]");
        if (!driver.findElements(success).isEmpty()) {
            System.out.println("  Success element visible ✓");
            return true;
        }

        System.out.println("  Registration uncertain. Alert='" + alertText + "' URL=" + url);
        return false;
    }

    public void clickBackToLogin() {
        try {
            WebElement link = wait.until(ExpectedConditions.elementToBeClickable(backToLoginLink));
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

    public boolean isRegistrationSuccessful() {
        return isRegistrationSuccessful(driver.switchTo().alert().getText());
    }
}