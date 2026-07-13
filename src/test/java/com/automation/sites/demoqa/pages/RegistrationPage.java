package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class RegistrationPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    private static final String REGISTER_URL = "https://demoqa.com/register";

    // IDs verified from compiled RegistrationPage.class:
    // firstname, lastname, userName, email, password, register, back
    private final By firstNameInput  = By.id("firstname");
    private final By lastNameInput   = By.id("lastname");
    private final By userNameInput   = By.id("userName");
    private final By emailInput      = By.id("email");
    private final By passwordInput   = By.id("password");
    private final By registerButton  = By.id("register");
    private final By backToLoginLink = By.id("gotologin");

    public RegistrationPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToRegistration() {
        driver.get(REGISTER_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
        sleep(1500);
        System.out.println("  Navigated to registration page");
    }

    /**
     * Fills one field:
     *  - JS scrollIntoView → fully visible
     *  - JS click → focus without overlay intercept
     *  - Ctrl+A + Delete → clear without React re-render side-effects
     *  - typeHumanLike → char-by-char (same as original compiled class)
     *  - NO TAB → TAB was jumping to reCAPTCHA and hiding the email field
     *  - Reads back value and logs it for debugging
     */
    private void fillField(By locator, String value, String fieldName) {
        WebElement el = wait.until(
                ExpectedConditions.visibilityOfElementLocated(locator));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        sleep(300);

        js.executeScript("arguments[0].click();", el);
        sleep(300);

        el.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        sleep(100);
        el.sendKeys(Keys.DELETE);
        sleep(100);

        HumanActions.typeHumanLike(el, value);
        sleep(300);

        // Read back what actually got typed — crucial for debugging
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

        sleep(600);

        WebElement btn = wait.until(
                ExpectedConditions.presenceOfElementLocated(registerButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        sleep(400);
        js.executeScript("arguments[0].click();", btn);
        System.out.println("  Register clicked");

        try {
            new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(ExpectedConditions.alertIsPresent());
            String alertText = driver.switchTo().alert().getText();
            System.out.println("  Alert: " + alertText);
            driver.switchTo().alert().accept();
        } catch (TimeoutException e) {
            System.out.println("  No alert appeared");
        }

        System.out.println("  URL after register: " + driver.getCurrentUrl());
    }

    public boolean isRegistrationSuccessful() {
        String url = driver.getCurrentUrl();
        if (url.contains("/login")) {
            System.out.println("  Redirected to /login ✓");
            return true;
        }
        By success = By.xpath(
                "//*[contains(text(),'registered') or contains(text(),'success')]");
        if (!driver.findElements(success).isEmpty()) {
            System.out.println("  Success element visible ✓");
            return true;
        }
        System.out.println("  Registration uncertain. URL: " + url);
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
            driver.get("https://demoqa.com/login");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("userName")));
        }
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}