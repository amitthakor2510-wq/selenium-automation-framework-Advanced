package com.automation.core.keyword;

import com.automation.core.budget.PerfSamples;
import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.KeywordExecutionException;
import com.automation.core.selfhealing.SelfHealingEngine;
import com.automation.core.utils.CaptchaSolver;
import com.automation.core.utils.ElementUtils;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.SensitiveData;
import com.automation.core.utils.ScreenshotUtil;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** CALL nesting guard: "file#testCase" of every script currently being executed via CALL. */
    private final Deque<String> callStack = new ArrayDeque<>();
    private static final int MAX_CALL_DEPTH = 10;
    /** "x,y", "x;y" or "xXy" pixel offset for DRAG_AND_DROP; ';' and 'x' avoid quoting the comma in a CSV cell. */
    private static final Pattern DRAG_OFFSET =
        Pattern.compile("(-?\\d+)\\s*[,;xX]\\s*(-?\\d+)");

    public KeywordEngine(WebDriver driver, ObjectRepository repo) {
        KeywordVariables.clear();
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
            case TYPE -> typeInto(step);
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
            case DOUBLE_CLICK -> new Actions(driver).doubleClick(clickable(locator(step))).perform();
            case RIGHT_CLICK -> new Actions(driver).contextClick(clickable(locator(step))).perform();
            case JS_CLICK -> jsClick(locator(step));
            case CHECK -> setChecked(step, true);
            case UNCHECK -> setChecked(step, false);
            case SELECT_BY_INDEX -> new Select(waitVisible(locator(step))).selectByIndex(parseInt(step, step.getTestData()));
            case UPLOAD_FILE -> uploadFile(step);
            case DRAG_AND_DROP -> dragAndDrop(step);
            case SWITCH_TO_WINDOW -> switchToWindow(step);
            case CLOSE_WINDOW -> closeWindow();
            case SWITCH_TO_PARENT_FRAME -> driver.switchTo().parentFrame();
            case WAIT_FOR_VISIBLE -> waitFor(step, true);
            case WAIT_FOR_NOT_VISIBLE -> waitFor(step, false);
            case VERIFY_ATTRIBUTE -> verifyAttribute(step);
            case VERIFY_ELEMENT_COUNT -> verifyElementCount(step);
            case STORE_TEXT -> storeText(step);
            case CALL -> call(step);
            default -> throw new KeywordExecutionException("[KeywordEngine] Unhandled keyword: " + step.getKeyword());
        }
    }

    // ── Keyword implementations ──────────────────────────────────────────────

    private void navigate(String path) {
        // Timed for the performance budget (see PerfSamples) - measured around driver.get() only.
        long startedNanos = System.nanoTime();
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
        PerfSamples.recordPageLoad(path, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /**
     * TYPE step. HumanActions.type() only masks when the resolved By.toString() names a secret
     * field, but an object-repository entry can be a bare positional XPath (SAHMAT's password
     * field is one) whose string never contains "password" - so the typed secret would land in
     * the Allure step name. The repo KEY (e.g. sahmat.login.password) is the only place the
     * field's real name survives, so it is checked here and routed to typeSecret() when it looks
     * sensitive.
     */
    private void typeInto(KeywordStep step) {
        By target = locator(step);
        if (SensitiveData.isSensitiveName(step.getLocatorKey())) {
            HumanActions.typeSecret(driver, target, step.getTestData());
        } else {
            HumanActions.type(driver, target, step.getTestData());
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
                + ": expected text to contain '" + step.getRawExpected() + "' but was '" + actual + "'");
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
                + ": expected URL to contain '" + step.getRawExpected() + "' but was '" + actual + "'");
        }
    }

    private void verifyTitleContains(KeywordStep step) {
        String actual = driver.getTitle();
        if (!actual.contains(step.getExpected())) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": expected title to contain '" + step.getRawExpected() + "' but was '" + actual + "'");
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

    // -- Added for record-and-playback ---------------------------

    private WebElement clickable(By locator) {
        return SelfHealingEngine.findClickable(driver, wait, locator);
    }

    private void jsClick(By locator) {
        WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }

    private void setChecked(KeywordStep step, boolean desired) {
        By target = locator(step);
        WebElement element = waitVisible(target);
        String tag = element.getTagName();
        String type = element.getAttribute("type");
        boolean stateful = "input".equalsIgnoreCase(tag) && type != null
            && ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type));
        if (!stateful) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword() + " needs a locator that points at an "
                + "<input type=checkbox|radio>, but '" + step.getLocatorKey() + "' is <" + tag + (type == null ? "" : " type=" + type)
                + ">. For a custom-styled control use CLICK on its visible label instead.");
        }
        if (!desired && "radio".equalsIgnoreCase(type)) {
            throw new KeywordExecutionException("[KeywordEngine] UNCHECK cannot be applied to a radio button ('"
                + step.getLocatorKey() + "'): a radio group is only changed by selecting another option.");
        }
        if (element.isSelected() == desired) {
            return;
        }
        HumanActions.click(driver, target);
        if (waitVisible(target).isSelected() != desired) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword() + " clicked '" + step.getLocatorKey()
                + "' but it is still " + (desired ? "unchecked" : "checked"));
        }
    }

    private int parseInt(KeywordStep step, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new KeywordExecutionException("[KeywordEngine] " + step.getKeyword() + " needs a whole number but got '"
                + raw + "' (step " + step.getStepNo() + " of " + step.getTestCase() + ")");
        }
    }

    private void uploadFile(KeywordStep step) {
        String raw = step.getTestData();
        if (raw.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] UPLOAD_FILE requires testData = the file path");
        }
        Path file = Path.of(raw);
        if (!file.isAbsolute()) {
            file = Path.of("").toAbsolutePath().resolve(file);
        }
        file = file.normalize();
        if (!Files.isRegularFile(file)) {
            throw new KeywordExecutionException("[KeywordEngine] UPLOAD_FILE: file not found: " + file
                + " (relative paths resolve against the project root)");
        }
        // File inputs are very often visually hidden behind a styled button, so wait for presence only.
        WebElement input = wait.until(ExpectedConditions.presenceOfElementLocated(locator(step)));
        if (driver instanceof RemoteWebDriver remote) {
            try {
                remote.setFileDetector(new LocalFileDetector());
            } catch (RuntimeException e) {
                logger.debug("[KeywordEngine] Local driver needs no file detector: {}", e.getMessage());
            }
        }
        input.sendKeys(file.toString());
    }

    private void dragAndDrop(KeywordStep step) {
        String spec = step.getTestData().trim();
        if (spec.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] DRAG_AND_DROP requires testData = target "
                + "ObjectRepository key, or a pixel offset like 40;0 (use ';' or 'x' so a CSV cell needs no quotes)");
        }
        WebElement source = waitVisible(locator(step));
        Actions actions = new Actions(driver).clickAndHold(source).pause(Duration.ofMillis(150));
        Matcher offset = DRAG_OFFSET.matcher(spec);
        if (offset.matches()) {
            int dx = Integer.parseInt(offset.group(1));
            int dy = Integer.parseInt(offset.group(2));
            int steps = 8;
            for (int i = 0; i < steps; i++) {
                actions.moveByOffset(dx / steps, dy / steps).pause(Duration.ofMillis(30));
            }
            actions.moveByOffset(dx - (dx / steps) * steps, dy - (dy / steps) * steps);
        } else {
            WebElement target = waitVisible(repo.get(spec));
            actions.moveByOffset(5, 5).pause(Duration.ofMillis(100))
                .moveToElement(target).pause(Duration.ofMillis(100))
                .moveByOffset(1, 1).pause(Duration.ofMillis(100));
        }
        actions.release().perform();
    }

    private void switchToWindow(KeywordStep step) {
        String raw = step.getTestData().trim();
        final String spec = raw.isEmpty() ? "NEW" : raw;
        String current = currentHandleOrNull();
        String target;
        try {
            target = wait.until(d -> findWindow(d, spec, current));
        } catch (TimeoutException e) {
            restoreWindow(current);
            throw new KeywordExecutionException("[KeywordEngine] SWITCH_TO_WINDOW: no window matched '" + spec
                + "' within the timeout. Open windows: " + driver.getWindowHandles().size());
        }
        driver.switchTo().window(target);
    }

    private void closeWindow() {
        driver.close();
        List<String> remaining = new ArrayList<>(driver.getWindowHandles());
        if (remaining.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] CLOSE_WINDOW closed the last open window");
        }
        driver.switchTo().window(remaining.get(remaining.size() - 1));
    }

    /** A title:/url: search switches windows to inspect them; put the driver back if nothing matched. */
    private void restoreWindow(String handle) {
        if (handle == null) {
            return;
        }
        try {
            driver.switchTo().window(handle);
        } catch (RuntimeException e) {
            logger.debug("[KeywordEngine] Could not restore window {}: {}", handle, e.getMessage());
        }
    }

    private String currentHandleOrNull() {
        try {
            return driver.getWindowHandle();
        } catch (RuntimeException e) {
            return null; // the current window was closed; "NEW" then means "any remaining window"
        }
    }

    private static String findWindow(WebDriver d, String spec, String current) {
        List<String> handles = new ArrayList<>(d.getWindowHandles());
        String lower = spec.toLowerCase(Locale.ROOT);
        if (lower.equals("new")) {
            for (int i = handles.size() - 1; i >= 0; i--) {
                if (!handles.get(i).equals(current)) {
                    return handles.get(i);
                }
            }
            return null;
        }
        if (lower.equals("last")) {
            return handles.isEmpty() ? null : handles.get(handles.size() - 1);
        }
        if (lower.equals("main") || lower.equals("first")) {
            return handles.isEmpty() ? null : handles.get(0);
        }
        if (spec.matches("\\d+")) {
            int index = Integer.parseInt(spec);
            return index < handles.size() ? handles.get(index) : null;
        }
        boolean byTitle = lower.startsWith("title:");
        boolean byUrl = lower.startsWith("url:");
        if (byTitle || byUrl) {
            String needle = spec.substring(spec.indexOf(':') + 1).trim();
            for (String handle : handles) {
                d.switchTo().window(handle);
                String haystack = byTitle ? d.getTitle() : d.getCurrentUrl();
                if (haystack != null && haystack.contains(needle)) {
                    return handle;
                }
            }
            return null;
        }
        throw new KeywordExecutionException("[KeywordEngine] SWITCH_TO_WINDOW testData must be NEW, LAST, MAIN, "
            + "a 0-based index, title:<text> or url:<text> - got '" + spec + "'");
    }

    private void waitFor(KeywordStep step, boolean visible) {
        int seconds = ConfigReader.getInt("timeout", 10);
        String override = step.getTestData();
        if (!override.isBlank()) {
            seconds = parseInt(step, override);
        }
        WebDriverWait custom = new WebDriverWait(driver, Duration.ofSeconds(seconds));
        By target = locator(step);
        if (visible) {
            SelfHealingEngine.find(driver, custom, target);
        } else {
            custom.until(ExpectedConditions.invisibilityOfElementLocated(target));
        }
    }

    private void verifyAttribute(KeywordStep step) {
        String attribute = step.getTestData().trim();
        if (attribute.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] VERIFY_ATTRIBUTE requires testData = the attribute name");
        }
        String actual = waitVisible(locator(step)).getAttribute(attribute);
        String expected = step.getExpected();
        if (actual == null || (!expected.isEmpty() && !actual.contains(expected))) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": expected attribute '" + attribute + "' of " + step.getLocatorKey()
                + (expected.isEmpty() ? " to exist" : " to contain '" + step.getRawExpected() + "'")
                + " but was " + (actual == null ? "absent" : "'" + actual + "'"));
        }
    }

    private void verifyElementCount(KeywordStep step) {
        int expected = parseInt(step, step.getExpected());
        By target = locator(step);
        try {
            wait.until(d -> d.findElements(target).size() == expected);
        } catch (TimeoutException e) {
            throw new AssertionError("[KeywordEngine] " + step.getTestCase() + " step " + step.getStepNo()
                + ": expected " + expected + " element(s) for " + step.getLocatorKey() + " but found "
                + driver.findElements(target).size());
        }
    }

    private void storeText(KeywordStep step) {
        String name = step.getRawTestData().trim();
        if (name.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] STORE_TEXT requires testData = the variable name");
        }
        WebElement element = waitVisible(locator(step));
        String attribute = step.getRawExpected().trim();
        String value = attribute.isEmpty() ? element.getText().trim() : element.getAttribute(attribute);
        KeywordVariables.put(name, value);
    }

    private void call(KeywordStep step) {
        String spec = step.getRawTestData().trim();
        if (spec.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] CALL requires testData = 'TestCaseName' or "
                + "'path/to/script.csv#TestCaseName'");
        }
        int hash = spec.indexOf('#');
        String file = hash >= 0 ? spec.substring(0, hash).trim() : step.getSourceFile();
        String testCase = hash >= 0 ? spec.substring(hash + 1).trim() : spec;
        if (file == null || file.isEmpty() || testCase.isEmpty()) {
            throw new KeywordExecutionException("[KeywordEngine] CALL could not work out which script/test case to run from '"
                + spec + "' - use 'path/to/script.csv#TestCaseName'");
        }
        String id = file + "#" + testCase;
        if (callStack.contains(id) || callStack.size() >= MAX_CALL_DEPTH) {
            throw new KeywordExecutionException("[KeywordEngine] CALL cycle or nesting deeper than " + MAX_CALL_DEPTH
                + " at " + id + " (call chain: " + callStack + ")");
        }
        callStack.push(id);
        try {
            run(KeywordReader.readTestCase(file, testCase));
        } finally {
            callStack.pop();
        }
    }

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
