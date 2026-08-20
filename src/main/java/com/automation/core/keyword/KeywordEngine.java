package com.automation.core.keyword;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.KeywordExecutionException;
import com.automation.core.selfhealing.SelfHealingEngine;
import com.automation.core.utils.CaptchaSolver;
import com.automation.core.utils.ElementUtils;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(KeywordEngine.class);

    private final WebDriver driver;
    private final ObjectRepository repo;
    private final WebDriverWait wait;

    // Lazily created — most suites never use a captcha keyword, and
    // CaptchaSolver's constructor does tessdata resolution/validation that
    // no other step needs to pay for.
    private CaptchaSolver captchaSolver;

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
                throw new KeywordExecutionException("[KeywordEngine] Step failed: " + step, e);
            }
        }
    }

    private void execute(KeywordStep step) {
        switch (step.getKeyword()) {
            case NAVIGATE -> {
                navigate(step.getTestData());
                autoHandleCaptcha();
            }
            case CLICK -> HumanActions.click(driver, locator(step));
            case TYPE -> HumanActions.type(driver, locator(step), step.getTestData());
            case SET_TEXT -> setText(locator(step), step.getTestData());
            case CLEAR -> waitVisible(locator(step)).clear();
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
            case WAIT_FOR_PAGE_LOAD -> waitForPageLoad(step);
            case SOLVE_TEXT_CAPTCHA -> captchaSolver().solveTextCaptcha(driver, waitVisible(locator(step)), captchaInputField(step));
            case SOLVE_MATH_CAPTCHA -> captchaSolver().solveMathCaptcha(driver, waitVisible(locator(step)), captchaInputField(step));
            case SOLVE_CAPTCHA_WITH_AI -> captchaSolver().solveWithAI(driver, waitVisible(locator(step)), captchaInputField(step));
            case SOLVE_TEXT_CAPTCHA_IF_PRESENT -> solveTextCaptchaIfPresent(step);
            default -> throw new KeywordExecutionException("[KeywordEngine] Unhandled keyword: " + step.getKeyword());
        }
    }

    // ── Keyword implementations ──────────────────────────────────────────────

    private void navigate(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            driver.get(path);
        } else {
            // Strip a trailing slash from the configured base URL so a config
            // file with e.g. url=https://demoqa.com/ can't produce a double
            // slash (https://demoqa.com//webtables) when concatenated below.
            String baseUrl = ConfigReader.get("url");
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
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
            throw new KeywordExecutionException("[KeywordEngine] WAIT_SECONDS testData must be numeric, got: '" + secondsRaw + "'");
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
            throw new KeywordExecutionException("[KeywordEngine] Unknown key: '" + raw
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

    /**
     * SOLVE_TEXT_CAPTCHA / SOLVE_MATH_CAPTCHA / SOLVE_CAPTCHA_WITH_AI need two
     * elements (the captcha image and the field to type the answer into), but
     * KeywordStep only carries one locatorKey. Convention: locatorKey points
     * at the captcha image (same as every other keyword), and testData holds
     * the ObjectRepository key for the input field, e.g.:
     *   testCase,stepNo,keyword,locatorKey,testData,expected,description
     *   TC01,3,SOLVE_TEXT_CAPTCHA,demoqa.captchaImage,demoqa.captchaInput,,
     */
    private WebElement captchaInputField(KeywordStep step) {
        if (step.getTestData().isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword()
                + " requires testData to hold the ObjectRepository key of the input field "
                + "(step " + step.getStepNo() + " of " + step.getTestCase() + ")");
        }
        return waitVisible(repo.get(step.getTestData()));
    }

    private CaptchaSolver captchaSolver() {
        if (captchaSolver == null) {
            captchaSolver = new CaptchaSolver();
        }
        return captchaSolver;
    }

    // Deliberately broad, same "match common naming, ignore case" approach
    // as CaptchaSolver's locator lists — real sites don't share one loading-
    // spinner convention, so this pattern-matches id/class rather than
    // requiring a per-site locator. Best-effort only: see waitForPageLoad().
    private static final By LOADING_INDICATOR_LOCATOR = By.cssSelector(
        "[class*='spinner' i],[class*='loading' i],[class*='loader' i],"
            + "[id*='spinner' i],[id*='loading' i],[id*='loader' i]");

    /**
     * Waits for document.readyState == 'complete' — a real signal that the
     * browser has finished loading the page, instead of a fixed WAIT_SECONDS
     * guess — then, best-effort, also waits for any common loading-spinner/
     * overlay element to disappear (readyState alone doesn't know about an
     * SPA's own "Loading..." UI, which can still be up well after the
     * browser itself considers the page loaded). Never fails the test: a
     * page/SPA that's still settling after the wait gets a warning in the
     * log, and the script continues (the following step's own element wait
     * is still the real safety net) — and every wait here is bounded, so
     * this keyword itself can never be the reason a test hangs (see
     * DriverFactory.applyPageLoadTimeout() for the separate driver-level
     * ceiling that bounds driver.get() itself). testData is an optional
     * override of the readyState wait, in seconds; falls back to
     * pageLoad.timeout, then timeout.long, then a 15s default.
     */
    private void waitForPageLoad(KeywordStep step) {
        int seconds;
        String override = step.getTestData();
        if (override != null && !override.isBlank()) {
            try {
                seconds = Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                throw new KeywordExecutionException("[KeywordEngine] WAIT_FOR_PAGE_LOAD testData must be numeric "
                    + "seconds if provided, got: '" + override + "'");
            }
        } else {
            seconds = ConfigReader.getInt("pageLoad.timeout", ConfigReader.getInt("timeout.long", 15));
        }

        try {
            new WebDriverWait(driver, Duration.ofSeconds(seconds)).until(d ->
                "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));
        } catch (Exception e) {
            // Not just TimeoutException — an unhandled alert or any other
            // transient WebDriver hiccup here must never fail/hang the test,
            // only a genuine element-level wait later should do that.
            logger.warn("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": page did not settle to document.readyState == 'complete' within " + seconds
                + "s (" + e.getClass().getSimpleName() + ") — continuing anyway (a slow page or an SPA "
                + "still hydrating). Later steps' own element waits remain the real safety net.");
        }

        waitForLoadingIndicatorToClear(step, Math.min(seconds, 10));
    }

    /**
     * Best-effort second half of WAIT_FOR_PAGE_LOAD: if a common loading-
     * spinner/overlay element is visible, wait a short bounded time for it
     * to disappear. No-ops instantly (the common case) if nothing matching
     * LOADING_INDICATOR_LOCATOR is on the page, and never throws — a stuck
     * spinner is exactly the kind of thing this step is meant to ride out,
     * not fail on.
     */
    private void waitForLoadingIndicatorToClear(KeywordStep step, int seconds) {
        try {
            List<WebElement> indicators = driver.findElements(LOADING_INDICATOR_LOCATOR);
            if (indicators.isEmpty()) {
                return;
            }
            new WebDriverWait(driver, Duration.ofSeconds(seconds))
                .until(ExpectedConditions.invisibilityOfAllElements(indicators));
        } catch (Exception e) {
            logger.info("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": a loading indicator was still visible after " + seconds
                + "s — continuing anyway rather than blocking the test on it.");
        }
    }

    /**
     * Forgiving counterpart to SOLVE_TEXT_CAPTCHA — same row shape
     * (locatorKey = CAPTCHA image, testData = ObjectRepository key of the
     * answer input), but waits up to captcha.wait.seconds (falls back to
     * timeout.long) for the image to actually appear, and simply logs +
     * moves on instead of failing the test if it never does. Use this for
     * CAPTCHAs that render conditionally/slowly (e.g. SAHMAT's login
     * sub-module) where a missing CAPTCHA on a given run is expected, not a
     * broken test. Clicks the input field first (best-effort, matching the
     * "click the field, then let the solver fill it" flow) before handing
     * off to CaptchaSolver.solveTextCaptcha.
     */
    private void solveTextCaptchaIfPresent(KeywordStep step) {
        if (step.getTestData().isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword()
                + " requires testData to hold the ObjectRepository key of the input field "
                + "(step " + step.getStepNo() + " of " + step.getTestCase() + ")");
        }

        int waitSeconds = ConfigReader.getInt("captcha.wait.seconds", ConfigReader.getInt("timeout.long", 15));
        WebElement image;
        try {
            image = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds))
                .until(ExpectedConditions.visibilityOfElementLocated(locator(step)));
        } catch (TimeoutException | NoSuchElementException e) {
            logger.info("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": CAPTCHA image (" + step.getLocatorKey() + ") did not appear within " + waitSeconds
                + "s — treating this run as having no CAPTCHA and continuing without failing the test.");
            return;
        }

        WebElement input;
        try {
            input = waitVisible(repo.get(step.getTestData()));
        } catch (Exception e) {
            logger.warn("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": CAPTCHA image was found but the input field (" + step.getTestData()
                + ") was not — skipping the solve, test continues.");
            return;
        }

        try {
            input.click();
        } catch (Exception e) {
            logger.debug("[KeywordEngine] Could not click the CAPTCHA input before solving (non-fatal): {}",
                e.getMessage());
        }

        captchaSolver().solveTextCaptcha(driver, image, input);
    }

    /**
     * Best-effort automatic CAPTCHA handling after every NAVIGATE step, on
     * top of the explicit SOLVE_TEXT_CAPTCHA/SOLVE_MATH_CAPTCHA/
     * SOLVE_CAPTCHA_WITH_AI keywords above. A CSV/Excel/YAML/JSON script
     * that never mentions a CAPTCHA keyword still gets covered automatically
     * if the page it navigates to happens to render one — mirrors
     * BasePage.navigateTo()'s identical hook for plain Page-Object tests, so
     * keyword-driven and Page-Object suites behave the same way here. No-ops
     * instantly when nothing CAPTCHA-like is on the page (the common case)
     * and never throws — see CaptchaSolver.autoSolveIfPresent(). Disable
     * with captcha.autoDetect.enabled=false.
     */
    private void autoHandleCaptcha() {
        if (!ConfigReader.getBoolean("captcha.autoDetect.enabled", true)) {
            return;
        }
        try {
            if (CaptchaSolver.detectCaptchaImage(driver).isPresent()) {
                captchaSolver().autoSolveIfPresent(driver);
            }
        } catch (Exception e) {
            logger.warn("[KeywordEngine] Auto CAPTCHA detection/solve failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private By locator(KeywordStep step) {
        if (step.getLocatorKey().isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword()
                + " requires a locatorKey (step " + step.getStepNo() + " of " + step.getTestCase() + ")");
        }
        return repo.get(step.getLocatorKey());
    }

    /** Routed through SelfHealingEngine — see BasePage.waitVisible for why. */
    private WebElement waitVisible(By locator) {
        return SelfHealingEngine.find(driver, wait, locator);
    }

    private boolean isDisplayed(By locator) {
        return ElementUtils.isDisplayed(driver, locator);
    }
}
