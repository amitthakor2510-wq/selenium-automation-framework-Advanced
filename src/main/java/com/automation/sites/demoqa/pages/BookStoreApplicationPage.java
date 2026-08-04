package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

import static java.util.stream.Collectors.*;
import java.util.logging.Logger;

public class BookStoreApplicationPage extends BasePage {

    private static final Logger logger = Logger.getLogger(BookStoreApplicationPage.class.getName());

    // ── Login ───────────────────────────────────────────────────────────────────
    private final By usernameField = By.id("userName");
    private final By passwordField = By.id("password");
    private final By loginButton   = By.id("login");
    private final By loginError    = By.id("output");
    private final By loggedInLabel = By.id("userName-value");
    private final By newUserButton = By.id("newUser");

    // ── Logout ──────────────────────────────────────────────────────────────────
    private final By logoutButton  = By.id("submit");

    // ── Book store ──────────────────────────────────────────────────────────────
    // DemoQA's book store page now renders a plain semantic <table>/<tr>/<td>
    // instead of the old react-table div grid — confirmed gone from the live
    // DOM (target/debug-dumps/bookstablenotfound-*.html), same redesign
    // already confirmed and fixed in WebTablesPage. Book title links live in
    // "table tbody a[href]"; there's no separate "no data" element in the
    // confirmed markup (noDataDiv kept as a defensive no-op in case demoqa
    // adds one back for empty results).
    private final By searchBox = By.id("searchBox");
    private final By bookLinks = By.cssSelector("table tbody a[href]");
    private final By noDataDiv = By.cssSelector(".rt-noData");
    private final By tableRows = By.cssSelector("table tbody tr");

    // ── Detail ──────────────────────────────────────────────────────────────────
    // CONFIRMED from live markup (target/debug-dumps/bookdetailvalueISBN-*.html):
    // "Back To Book Store" and "Add To Your Collection" are BOTH present on
    // the detail page at once, and both buttons share the same duplicate
    // id="addNewRecordButton". By.id() always resolves to the first DOM
    // match ("Back To Book Store"), so a shared id locator can never reach
    // "Add To Your Collection" — the two need separate, text-based locators.
    private final By backToStoreBtn      = By.xpath("//button[normalize-space()='Back To Book Store']");
    private final By addToCollectionBtn  = By.xpath("//button[normalize-space()='Add To Your Collection']");

    public BookStoreApplicationPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    public void navigateToLogin() {
        navigateTo("/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));
    }

    public void navigateToRegister() {
        navigateTo("/register");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("firstname")));
    }

    public void navigateToBookStore() {
        navigateTo("/books");
        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));

        // NOTE: previously also waited on presenceOfElementLocated([role='table'])
        // before this, then on .rt-tr-group / .rt-noData. Both guesses timed
        // out even though the table visibly renders on screen — demoqa's
        // react-table markup has apparently changed and neither selector
        // matches current DOM. Rather than guess a third time, dump the real
        // page source on timeout so the locators can be fixed from actual
        // markup instead of another guess.
        try {
            wait.until(d -> !d.findElements(noDataDiv).isEmpty()
                || (!d.findElements(tableRows).isEmpty()
                && d.findElements(tableRows).stream()
                .anyMatch(r -> !r.getText().trim().isEmpty())));
        } catch (TimeoutException e) {
            dumpPageForDebugging("books-table-not-found");
            throw e;
        }

        HumanActions.pause();
    }

    // dumpPageForDebugging(label) is inherited from BasePage — see there
    // for the shared implementation (was a duplicate of this class's own
    // copy until consolidated).

    public void navigateToProfile() {
        navigateTo("/profile");
        wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel));
    }

    // ── Login / Logout ──────────────────────────────────────────────────────────

    public void login(String username, String password) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField));

        // HumanActions.type() has no read-back/retry — it just sends keys once.
        // DemoQA's React-controlled inputs occasionally drop or truncate
        // keystrokes under fast/automated typing (the same behavior already
        // worked around in RegistrationPage.fillField), which is why the
        // username field previously submitted a truncated value even though
        // registration itself succeeded. Verify-and-retry here the same way.
        typeVerified(usernameField, username, "Username");
        typeVerified(passwordField, password, "Password");

        WebElement btn = driver.findElement(loginButton);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);

        wait.until(d ->
            d.getCurrentUrl().contains("/profile") ||
                !d.findElements(loginError).isEmpty()
        );

        if (!driver.getCurrentUrl().contains("/profile")) {
            String error = getLoginErrorMessage();
            logger.warning("  Login did not reach /profile. Error text: '" + error + "'");
        }
    }

    /**
     * Types into a login field and verifies the field actually contains what
     * was typed, retrying up to 3 times. Mirrors RegistrationPage.fillField —
     * needed because a plain sendKeys can silently under-deliver characters
     * into DemoQA's React-controlled inputs.
     */
    private void typeVerified(By locator, String value, String fieldName) {
        final int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebElement el = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));

            js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
            js.executeScript("arguments[0].click();", el);

            el.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            el.sendKeys(Keys.DELETE);

            HumanActions.typeHumanLike(el, value);

            String actual;
            try {
                actual = el.getAttribute("value");
            } catch (StaleElementReferenceException e) {
                actual = null;
            }

            if (value.equals(actual)) {
                return;
            }
            logger.info("  " + fieldName + ": typed='" + value
                + "' actual='" + actual + "' (attempt " + attempt + "/" + maxAttempts + ") — retrying");
        }

        throw new IllegalStateException(
            fieldName + " field still didn't contain the expected value after "
                + maxAttempts + " attempts — page may be unstable");
    }

    public boolean isLoggedIn() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel));
            return true;
        } catch (TimeoutException e) {
            dumpLoggedInDiagnostics();
            return false;
        }
    }

    /**
     * Runs only when isLoggedIn() times out waiting for loggedInLabel
     * (By.xpath("//span[contains(@id,'userName')]")). 5 seconds already rules
     * out a simple render race, so this surfaces what's ACTUALLY on the page
     * instead of guessing further: current URL, every element whose id
     * contains "userName" (case-insensitive) with its tag/text/visibility,
     * and a screenshot.
     */
    private void dumpLoggedInDiagnostics() {
        try {
            logger.info("  --- Diagnostics: isLoggedIn() timed out ---");
            logger.info("  URL: " + driver.getCurrentUrl());

            @SuppressWarnings("unchecked")
            List<Object> matches = (List<Object>) js.executeScript(
                "var out = []; " +
                    "document.querySelectorAll('[id]').forEach(function(el) { " +
                    "  if (el.id.toLowerCase().indexOf('username') !== -1) { " +
                    "    out.push({tag: el.tagName, id: el.id, text: el.textContent, " +
                    "              visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)}); " +
                    "  } " +
                    "}); " +
                    "return out;");

            if (matches == null || matches.isEmpty()) {
                logger.info("  No element anywhere on the page has an id containing 'userName' (case-insensitive).");
            } else {
                for (Object m : matches) {
                    logger.info("  Match: " + m);
                }
            }

            String screenshotPath = ScreenshotUtil.captureScreenshot(driver, "login_not_detected");
            logger.info("  Screenshot saved: " + screenshotPath);
            logger.info("  --- End diagnostics ---");
        } catch (Exception e) {
            logger.warning("  isLoggedIn diagnostics capture failed: " + e.getMessage());
        }
    }

    public String getLoggedInUserName() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(loggedInLabel))
            .getText().trim();
    }

    public String getLoginErrorMessage() {
        if (driver.findElements(loginError).isEmpty()) {
            return "";
        }
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

        // Wait for filter to apply instead of raw Thread.sleep
        HumanActions.pause();
        wait.until(d -> {
            List<WebElement> rows = d.findElements(tableRows);
            return rows.stream().anyMatch(r -> {
                try { return !r.getText().trim().isEmpty(); }
                catch (StaleElementReferenceException e) { return false; }
            });
        });
    }

    public int getVisibleBookCount() {
        if (!driver.findElements(noDataDiv).isEmpty()) {
            return 0;
        }
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
            .collect(toList());
    }

    public void clickFirstBook() {
        WebElement first = driver.findElements(bookLinks).stream()
            .filter(e -> {
                try { return !e.getText().trim().isEmpty(); }
                catch (StaleElementReferenceException ex) { return false; }
            })
            .findFirst()
            .orElseThrow(() -> new RuntimeException("No books found in store"));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", first);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", first);
        // Confirmed from live markup: book links now go to /books?search=<isbn>
        // (was /books?book=<isbn> under the old react-table version).
        try {
            wait.until(ExpectedConditions.urlContains("/books?search="));
        } catch (TimeoutException e) {
            dumpPageForDebugging("book-detail-url-not-reached");
            throw e;
        }
    }

    public void clickBookByTitle(String title) {
        By link = By.cssSelector("table tbody a[href]");
        WebElement el = wait.until(d -> d.findElements(link).stream()
            .filter(e -> {
                try { return e.getText().trim().equalsIgnoreCase(title); }
                catch (StaleElementReferenceException ex) { return false; }
            })
            .findFirst().orElse(null));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", el);
        try {
            wait.until(ExpectedConditions.urlContains("/books?search="));
        } catch (TimeoutException e) {
            dumpPageForDebugging("book-detail-url-not-reached-by-title");
            throw e;
        }
    }

    public String getBookDetailValue(String label) {
        // Confirmed from live markup (target/debug-dumps/bookdetailvalueISBN-*.html):
        // each field is a <div id="{field}-wrapper"> containing a name label
        // and, in a sibling ".col-md-9" div, the value label. Every value
        // label reuses id="userName-value" (duplicate IDs on the real page),
        // so matching by that id is useless — scope by the wrapper instead.
        // Wrapper id casing is inconsistent: "ISBN-wrapper" keeps ISBN
        // uppercase, everything else is lowercase ("author-wrapper", etc).
        String wrapperId = "ISBN".equalsIgnoreCase(label)
            ? "ISBN-wrapper"
            : label.toLowerCase() + "-wrapper";
        By valueLocator = By.cssSelector("#" + wrapperId + " .col-md-9 label");
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(valueLocator))
                .getText().trim();
        } catch (TimeoutException e) {
            dumpPageForDebugging("book-detail-value-" + label);
            throw e;
        }
    }

    public void clickBackToBookStore() {
        WebElement btn = wait.until(
            ExpectedConditions.elementToBeClickable(backToStoreBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));
    }

    /**
     * Clicks "Add To Your Collection" on the book detail page. CONFIRMED
     * both this button and "Back To Book Store" are present at the same
     * time and share a duplicate id — see addToCollectionBtn/backToStoreBtn
     * comment above for why this must use the text-based locator, not id.
     *
     * CONFIRMED: the click triggers a native JS alert ("Book added to your
     * collection.") that must be accepted before any further driver call.
     * CONFIRMED (from a real run): accepting the alert does NOT navigate
     * anywhere — the book is added silently and the driver stays on this
     * same book detail page. Callers must navigate to /profile themselves
     * afterward if they need to see the updated collection.
     */
    public void addBookToCollection() {
        WebElement btn = wait.until(
            ExpectedConditions.elementToBeClickable(addToCollectionBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);

        wait.until(ExpectedConditions.alertIsPresent());
        String alertText = driver.switchTo().alert().getText();
        logger.info("  [BookStoreApplicationPage] Alert: " + alertText);
        driver.switchTo().alert().accept();
    }
}
