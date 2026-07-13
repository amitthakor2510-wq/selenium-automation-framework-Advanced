package com.automation.sites.demoqa.pages;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class BookStoreApplicationPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;
    private final String baseUrl;

    // ── Login ───────────────────────────────────────────────────────────────────
    private final By usernameField = By.id("userName");
    private final By passwordField = By.id("password");
    private final By loginButton   = By.id("login");
    private final By loginError    = By.id("output");
    private final By loggedInLabel = By.xpath("//span[contains(@id,'userName')]");
    private final By newUserButton = By.id("newUser");

    // ── Logout ──────────────────────────────────────────────────────────────────
    private final By logoutButton  = By.id("submit");

    // ── Book store ──────────────────────────────────────────────────────────────
    private final By searchBox = By.id("searchBox");
    private final By bookLinks = By.xpath("//div[@role='table']//a[@href]");
    private final By noDataDiv = By.cssSelector(".rt-noData");

    // ── Detail ──────────────────────────────────────────────────────────────────
    private final By backToStoreBtn = By.id("addNewRecordButton");

    public BookStoreApplicationPage(WebDriver driver) {
        this.driver  = driver;
        this.wait    = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.js      = (JavascriptExecutor) driver;
        this.baseUrl = ConfigReader.get("url");
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    public void navigateToLogin() {
        driver.get(baseUrl + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
    }

    public void navigateToRegister() {
        driver.get(baseUrl + "/register");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("firstname")));
    }

    public void navigateToBookStore() {
        driver.get(baseUrl + "/books");

        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));

        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("[role='table']")));

        try { Thread.sleep(2000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        HumanActions.pause();
    }

    public void navigateToProfile() {
        driver.get(baseUrl + "/profile");
        wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel));
    }

    // ── Login / Logout ──────────────────────────────────────────────────────────

    public void login(String username, String password) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
        HumanActions.type(driver, usernameField, username);
        HumanActions.type(driver, passwordField, password);

        WebElement btn = driver.findElement(loginButton);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);

        wait.until(d ->
                d.getCurrentUrl().contains("/profile") ||
                        !d.findElements(loginError).isEmpty()
        );
    }

    public boolean isLoggedIn() {
        try {
            return !driver.findElements(loggedInLabel).isEmpty()
                    && driver.findElement(loggedInLabel).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    public String getLoggedInUserName() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel))
                .getText().trim();
    }

    public String getLoginErrorMessage() {
        if (driver.findElements(loginError).isEmpty()) return "";
        return driver.findElement(loginError).getText().trim();
    }

    public void clickNewUser() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(newUserButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("firstname")));
    }

    public void logout() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(logoutButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.urlContains("/login"));
    }

    // ── Book Store ──────────────────────────────────────────────────────────────

    public void searchBook(String keyword) {
        WebElement box = wait.until(
                ExpectedConditions.visibilityOfElementLocated(searchBox));
        box.clear();
        box.sendKeys(keyword);
        try { Thread.sleep(800); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getVisibleBookCount() {
        if (!driver.findElements(noDataDiv).isEmpty()) return 0;
        return (int) driver.findElements(bookLinks).stream()
                .filter(e -> {
                    try { return !e.getText().trim().isEmpty(); }
                    catch (StaleElementReferenceException ex) { return false; }
                })
                .count();
    }

    public List<String> getBookTitles() {
        return driver.findElements(bookLinks).stream()
                .map(e -> {
                    try { return e.getText().trim(); }
                    catch (StaleElementReferenceException ex) { return ""; }
                })
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    public void clickFirstBook() {
        WebElement first = driver.findElements(bookLinks).stream()
                .filter(e -> {
                    try { return !e.getText().trim().isEmpty(); }
                    catch (StaleElementReferenceException ex) { return false; }
                })
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No books found"));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", first);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", first);
        wait.until(ExpectedConditions.urlContains("/books?book="));
    }

    public void clickBookByTitle(String title) {
        By link = By.xpath(
                "//div[@role='table']//a[contains(text(),'" + title + "')]");
        WebElement el = wait.until(ExpectedConditions.elementToBeClickable(link));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", el);
        wait.until(ExpectedConditions.urlContains("/books?book="));
    }

    public String getBookDetailValue(String label) {
        By locator = By.xpath(
                "//label[normalize-space(text())='" + label
                        + "']/following-sibling::label[1]");
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator))
                .getText().trim();
    }

    public void clickBackToBookStore() {
        WebElement btn = wait.until(
                ExpectedConditions.elementToBeClickable(backToStoreBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));
    }
}