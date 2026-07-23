package com.automation.core.keyword;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Executes a list of KeywordStep against a live WebDriver session, resolving
 * locators through an ObjectRepository. This is the whole point of
 * keyword-driven testing: new test cases become new rows in a data file,
 * not new Java classes — the engine and the keyword vocabulary don't change.
 *
 * Also covers keyboard-driven checks via PRESS_KEY, which sends raw
 * org.openqa.selenium.Keys (TAB, ENTER, ESCAPE, ARROW_*, SPACE, ...) either
 * to a specific element or to whatever currently has focus — useful for
 * verifying a flow is fully operable without a mouse.
 *
 * Usage:
 *   ObjectRepository repo = ObjectRepository.load("objectrepository/saucedemo.properties");
 *   List<KeywordStep> steps = KeywordReader.readTestCase(scriptPath, "TC01_ValidLogin");
 *   new KeywordEngine(driver, repo).run(steps);
 */
public class KeywordEngine {

    private static final Logger logger = Logger.getLogger(KeywordEngine.class.getName());

    private final WebDriver driver;
    private final ObjectRepository repo;
    private final WebDriverWait wait;

    public KeywordEngine(WebDriver driver, ObjectRepository repo) {
        this.driver = driver;
        this.repo = repo;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
    }

    /** Executes every step in order, failing fast with context about which step broke. */
    public void run(List<KeywordStep> steps) {
        for (KeywordStep step : steps) {
            try {
                logger.info("[KeywordEngine] " + step);
                execute(step);
            } catch (AssertionError e) {
                throw e; // verification failures already carry a clear message
            } catch (Exception e) {
                throw new RuntimeException("[KeywordEngine] Step failed: " + step, e);
            }
        }
    }

    private void execute(KeywordStep step) {
        switch (step.getKeyword()) {
            case NAVIGATE -> navigate(step.getTestData());
            case CLICK -> HumanActions.click(driver, locator(step));
            case TYPE -> HumanActions.type(driver, locator(step), step.getTestData());
            case SET_TEXT -> setText(locator(step), step.getTestData());
            case CLEAR -> driver.findElement(locator(step)).clear();
            case SELECT_BY_TEXT -> new Select(waitVisible(locator(step))).selectByVisibleText(step.getTestData());
            case SELECT_BY_VALUE -> new Select(waitVisible(locator(step))).selectByValue(step.getTestData());
            case HOVER -> new Actions(driver).moveToElement(waitVisible(locator(step))).perform();
            case SCROLL_TO -> scrollTo(locator(step));
            case WAIT_SECONDS -> sleepSeconds(step.getTestData());
            case PRESS_KEY -> pressKey(step);
            case VERIFY_TEXT -> verifyText(step);
            case VERIFY_DISPLAYED -> verifyDisplayed(step, true);
            case VERIFY_NOT_DISPLAYED -> verifyDisplayed(step, false);
            case VERIFY_URL_CONTAINS -> verifyUrlContains(step);
            case VERIFY_TITLE_CONTAINS -> verifyTitleContains(step);
            case SWITCH_TO_FRAME -> driver.switchTo().frame(waitVisible(locator(step)));
            case SWITCH_TO_DEFAULT_CONTENT -> driver.switchTo().defaultContent();
            case ACCEPT_ALERT -> driver.switchTo().alert().accept();
            case DISMISS_ALERT -> driver.switchTo().alert().dismiss();
            case SCREENSHOT -> ScreenshotUtil.captureScreenshot(driver,
                    step.getTestData().isEmpty() ? step.getTestCase() + "_step" + step.getStepNo() : step.getTestData());
        }
    }

    // ── Keyword implementations ──────────────────────────────────────────────

    private void navigate(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            driver.get(path);
        } else {
            String baseUrl = ConfigReader.get("url");
            driver.get(path.isEmpty() ? baseUrl
                    : baseUrl + (path.startsWith("/") ? path : "/" + path));
        }
    }

    private void setText(By locator, String text) {
        WebElement element = waitVisible(locator);
        element.clear();
        element.sendKeys(text);
    }

    private void scrollTo(By locator) {
        WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
    }

    private void sleepSeconds(String secondsRaw) {
        try {
            double seconds = Double.parseDouble(secondsRaw.trim());
            Thread.sleep((long) (seconds * 1000));
        } catch (NumberFormatException e) {
            throw new RuntimeException("[KeywordEngine] WAIT_SECONDS testData must be numeric, got: '" + secondsRaw + "'");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends a keyboard key (Keys enum name, e.g. ENTER/TAB/ESCAPE/ARROW_DOWN/SPACE)
     * either to a named element (if locatorKey is set) or to whatever element
     * currently has focus — the natural way to drive a UI purely by keyboard,
     * e.g. TAB, TAB, ENTER through a form without a single click.
     */
    private void pressKey(KeywordStep step) {
        Keys key = resolveKey(step.getTestData());
        if (!step.getLocatorKey().isEmpty()) {
            waitVisible(locator(step)).sendKeys(key);
        } else {
            new Actions(driver).sendKeys(key).perform();
        }
    }

    private Keys resolveKey(String raw) {
        try {
            return Keys.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("[KeywordEngine] Unknown key: '" + raw
                    + "'. Use a org.openqa.selenium.Keys name, e.g. ENTER, TAB, ESCAPE, ARROW_DOWN, SPACE");
        }
    }

    private void verifyText(KeywordStep step) {
        String actual = waitVisible(locator(step)).getText().trim();
        if (!actual.contains(step.getExpected())) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                    + ": expected text to contain '" + step.getExpected() + "' but was '" + actual + "'");
        }
    }

    private void verifyDisplayed(KeywordStep step, boolean shouldBeDisplayed) {
        boolean displayed = isDisplayed(locator(step));
        if (displayed != shouldBeDisplayed) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                    + ": expected element " + step.getLocatorKey()
                    + (shouldBeDisplayed ? " to be displayed but it was not" : " to be hidden but it was displayed"));
        }
    }

    private void verifyUrlContains(KeywordStep step) {
        String actual = driver.getCurrentUrl();
        if (!actual.contains(step.getExpected())) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                    + ": expected URL to contain '" + step.getExpected() + "' but was '" + actual + "'");
        }
    }

    private void verifyTitleContains(KeywordStep step) {
        String actual = driver.getTitle();
        if (!actual.contains(step.getExpected())) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                    + ": expected title to contain '" + step.getExpected() + "' but was '" + actual + "'");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private By locator(KeywordStep step) {
        if (step.getLocatorKey().isEmpty()) {
            throw new RuntimeException("[KeywordEngine] " + step.getKeyword()
                    + " requires a locatorKey (step " + step.getStepNo() + " of " + step.getTestCase() + ")");
        }
        return repo.get(step.getLocatorKey());
    }

    private WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    private boolean isDisplayed(By locator) {
        try {
            return !driver.findElements(locator).isEmpty() && driver.findElement(locator).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}
