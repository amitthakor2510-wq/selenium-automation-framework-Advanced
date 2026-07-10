package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class BookStoreApplicationPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final WebDriverWait longWait; // 30s for slow book table render
    private final JavascriptExecutor js;

    // ── URLs ────────────────────────────────────────────────────────────────────
    private static final String LOGIN_URL    = "https://demoqa.com/login";
    private static final String BOOKS_URL    = "https://demoqa.com/books";
    private static final String REGISTER_URL = "https://demoqa.com/register";
    private static final String PROFILE_URL  = "https://demoqa.com/profile";

    // ── Register page ───────────────────────────────────────────────────────────
    private final By firstNameField   = By.id("firstname");
    private final By lastNameField    = By.id("lastname");
    private final By regUsernameField = By.id("userName");
    private final By regPasswordField = By.id("password");
    private final By captchaFrame     = By.cssSelector("iframe[title='reCAPTCHA']");
    private final By captchaCheckbox  = By.cssSelector(".recaptcha-checkbox-border");
    private final By registerButton   = By.id("register");
    private final By registerSuccess  = By.id("output");

    // ── Login page ──────────────────────────────────────────────────────────────
    private final By usernameField  = By.id("userName");
    private final By passwordField  = By.id("password");
    private final By loginButton    = By.id("login");
    private final By loggedInLabel  = By.id("userName-value");
    private final By loginError     = By.id("output");
    private final By logoutButton   = By.id("submit");
    private final By newUserButton  = By.id("newUser");

    // ── Book Store page ─────────────────────────────────────────────────────────
    private final By searchBox     = By.id("searchBox");
    private final By bookRows      = By.cssSelector(".rt-tbody .rt-tr-group");
    private final By bookLinks     = By.cssSelector(".rt-tbody .rt-tr-group .rt-td a");
    private final By noDataDiv     = By.cssSelector(".rt-noData");

    // ── Book Detail page ────────────────────────────────────────────────────────
    private final By backToStoreBtn = By.id("addNewRecordButton");

    public BookStoreApplicationPage(WebDriver driver) {
        this.driver   = driver;
        this.wait     = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.longWait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.js       = (JavascriptExecutor) driver;
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    public void navigateToRegister() {
        driver.get(REGISTER_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameField));
    }

    public void navigateToLogin() {
        driver.get(LOGIN_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
    }

    public void navigateToBookStore() {
        driver.get(BOOKS_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));

        // Wait for first book link to appear — stops as soon as books load
        wait.until(d -> {
            List<WebElement> links = d.findElements(bookLinks);
            return links.stream().anyMatch(e -> {
                try { return !e.getText().trim().isEmpty(); }
                catch (StaleElementReferenceException ex) { return false; }
            });
        });

        WebElement table = driver.findElement(By.cssSelector(".rt-tbody"));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", table);
        HumanActions.pause();
    }

    public void navigateToProfile() {
        driver.get(PROFILE_URL);
        wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel));
    }

    // ── Register ────────────────────────────────────────────────────────────────

    /**
     * Fills in the registration form.
     * NOTE: reCAPTCHA must be solved manually — Selenium cannot solve it.
     * This method fills the form and pauses for 15 seconds for manual solve,
     * then clicks Register. In CI, skip this test or use a pre-registered account.
     *
     * NEW CONCEPT — reCAPTCHA iframe:
     * The captcha widget is inside an iframe. To interact with it, you must
     * switchTo().frame() first. However, reCAPTCHA v2 detects automation and
     * will show a challenge. Manual intervention is required.
     */
    public void fillRegisterForm(String firstName, String lastName,
                                 String username, String password) {
        HumanActions.type(driver, firstNameField, firstName);
        HumanActions.type(driver, lastNameField, lastName);
        HumanActions.type(driver, regUsernameField, username);
        HumanActions.type(driver, regPasswordField, password);

        System.out.println(">>> Please solve the reCAPTCHA manually within 15 seconds...");
        try { Thread.sleep(15000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void clickRegister() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(registerButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
    }

    public String getRegisterMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(registerSuccess))
                .getText().trim();
    }

    // ── Login / Logout ──────────────────────────────────────────────────────────

    public void login(String username, String password) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
        HumanActions.type(driver, usernameField, username);
        HumanActions.type(driver, passwordField, password);

        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(loginButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
    }

    public boolean isLoggedIn() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel))
                    .isDisplayed();
        } catch (TimeoutException e) {
            return false;
        }
    }

    public String getLoggedInUserName() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel))
                .getText().trim();
    }

    public String getLoginErrorMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(loginError))
                .getText().trim();
    }

    /**
     * Clicks the "Log out" button on the login/profile page.
     * After logout, redirects to login page.
     */
    public void logout() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(logoutButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        // After logout, login page should reappear
        wait.until(ExpectedConditions.visibilityOfElementLocated(loginButton));
    }

    public void clickNewUser() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(newUserButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameField));
    }

    // ── Book Store ──────────────────────────────────────────────────────────────

    /**
     * Searches for a keyword in the book store search box.
     * After typing, waits for the React table to re-filter.
     */
    public void searchBook(String keyword) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));
        box.clear();
        HumanActions.type(driver, searchBox, keyword);

        // Wait for table to re-render after filter
        HumanActions.pause();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Returns count of visible books with non-empty title links.
     */
    public int getVisibleBookCount() {
        if (!driver.findElements(noDataDiv).isEmpty()) return 0;
        return (int) driver.findElements(bookLinks).stream()
                .filter(e -> {
                    try { return !e.getText().trim().isEmpty(); }
                    catch (StaleElementReferenceException ex) { return false; }
                }).count();
    }

    /**
     * Returns list of all visible book titles.
     */
    public List<String> getBookTitles() {
        if (!driver.findElements(noDataDiv).isEmpty()) return Collections.emptyList();
        return driver.findElements(bookLinks).stream()
                .map(e -> {
                    try { return e.getText().trim(); }
                    catch (StaleElementReferenceException ex) { return ""; }
                })
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Clicks the first visible book in the table.
     */
    public void clickFirstBook() {
        longWait.until(ExpectedConditions.presenceOfElementLocated(bookLinks));
        HumanActions.pause();

        WebElement first = driver.findElements(bookLinks).stream()
                .filter(e -> {
                    try { return !e.getText().trim().isEmpty(); }
                    catch (StaleElementReferenceException ex) { return false; }
                })
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No book links found"));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", first);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", first);

        // Wait for detail page to load
        wait.until(ExpectedConditions.urlContains("/books?book="));
    }

    /**
     * Clicks a book by its exact title.
     */
    public void clickBookByTitle(String title) {
        By link = By.xpath(
                "//div[contains(@class,'rt-tbody')]//a[normalize-space()='" + title + "']");
        WebElement el = longWait.until(ExpectedConditions.elementToBeClickable(link));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", el);
        wait.until(ExpectedConditions.urlContains("/books?book="));
    }

    // ── Book Detail page ────────────────────────────────────────────────────────

    /**
     * Returns the value of a labelled field on the book detail page.
     * e.g. getBookDetailValue("Book Title :") → "Git Pocket Guide"
     */
    public String getBookDetailValue(String fieldLabel) {
        By locator = By.xpath(
                "//label[normalize-space(text())='" + fieldLabel
                        + "']/following-sibling::label[1]");
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator))
                .getText().trim();
    }

    public String getBookDetailTitle() {
        return getBookDetailValue("Book Title :");
    }

    public void clickBackToBookStore() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(backToStoreBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));
    }
}