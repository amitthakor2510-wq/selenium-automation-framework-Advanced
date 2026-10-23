package com.automation.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

/**
 * ============================================================================
 * REFERENCE TEMPLATE — not a real page, never run by any suite.
 * ============================================================================
 * This class exists purely so you (or anyone new to this framework) can open
 * ONE file and see every convention the real Page Objects follow, instead of
 * reverse-engineering them from a live site's page like DatePickerPage.java.
 *
 * It lives in its own `com.automation.template` package deliberately — every
 * testng-suites/*.xml <packages> entry points at `com.automation.sites.*` or
 * `com.automation.mobile.sites.*`, so this package is never matched and this
 * class is never picked up by TestNG. It still compiles and gets Checkstyle-
 * checked like any other file, so it can't silently drift out of date with a
 * real refactor the way a comment or a wiki page would.
 *
 * To build a REAL page object: copy this file into
 * src/main/java/com/automation/sites/<yoursite>/pages/, rename the class,
 * and replace the example locators/methods with real ones. (Or just run
 * Scripts/new-site.sh, which scaffolds a whole new site for you — this file
 * is for understanding the pattern, not for hand-copying every time.)
 * ============================================================================
 */
public class TemplatePage extends BasePage {

    // Every page object gets its own named logger — never use System.out or
    // System.err (see the framework's logging conventions doc). The logger
    // name should match the class name exactly, copy-pasted, not typed by
    // hand — a mismatched name here is a real bug we found and fixed across
    // 19 files in this framework: it doesn't error, it just silently
    // misattributes every log line to the wrong class in test output.
    private static final Logger logger = LoggerFactory.getLogger(TemplatePage.class);

    // ── Locators ─────────────────────────────────────────────────────────
    // Declare every locator as a `private final By` field at the top of the
    // class, never inline inside a method. This keeps every selector for
    // this page visible in one place, so a site redesign only means editing
    // this block, not hunting through every method.
    //
    // Prefer By.id() > By.cssSelector() > By.xpath(), in that order — id is
    // fastest and least likely to break on a CSS refactor; xpath is the
    // most brittle and slowest, and should be a last resort.
    private final By searchInput  = By.id("template-search-input");
    private final By searchButton = By.cssSelector("button[data-testid='template-search-button']");
    private final By resultsList  = By.cssSelector(".template-results-list");
    private final By resultItem   = By.cssSelector(".template-results-list .result-item");

    // Real sites sometimes swap out a UI library (see DatePickerPage.java's
    // comment on demoqa's Check Box widget going from react-checkbox-tree to
    // rc-tree mid-project) and break a locator with no warning. For a
    // locator you expect might be fragile, com.automation.core.utils.
    // SmartLocator.find(driver, wait, "description", primary, fallback...)
    // tries each locator in order and logs which one actually worked — see
    // DatePickerPage.java for a real example. Don't reach for it by default;
    // plain By locators above are the right choice for anything stable.

    public TemplatePage(WebDriver driver) {
        // Always just this one line — BasePage's constructor sets up
        // `driver`, `wait` (a WebDriverWait sized from the "timeout" config
        // key), and `js` (a JavascriptExecutor cast of the same driver) for
        // every page object to use. Don't re-implement any of those here.
        super(driver);
    }

    // ── Navigation ───────────────────────────────────────────────────────

    /**
     * Every page object that represents a distinct URL should have a
     * navigateXyz() method like this, rather than expecting the test class
     * to call driver.get(...) directly. navigateTo() (from BasePage) is
     * relative to the active site's configured "url" — see
     * src/test/resources/config/<site>.properties.
     */
    public void navigateToTemplatePage() {
        navigateTo("/template-example");
        // Wait for something that proves the page has actually finished
        // loading before returning control to the test — don't just call
        // navigateTo() and assume the page is ready.
        waitVisible(searchInput);
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /**
     * Use HumanActions.click()/.type() instead of calling
     * element.click()/.sendKeys() directly. Both already: wait for the
     * element to be clickable/visible first, add a randomized human-like
     * pause beforehand (configurable via human.pause.* — disabled entirely
     * for fast CI smoke runs via -Dhuman.pause.enabled=false), and show up
     * as a named step in the Allure report (@Step annotation on
     * HumanActions itself) — plain Selenium calls don't get any of that
     * for free.
     */
    public void search(String query) {
        logger.info("Searching template page for: " + query);
        HumanActions.type(driver, searchInput, query);
        HumanActions.click(driver, searchButton);
        // Wait for the RESULT of the action, not a fixed sleep — waitVisible
        // (from BasePage) blocks until the results list actually shows up,
        // capped at the configured timeout. Never Thread.sleep() to "wait
        // for a page to catch up" — that either wastes time when the page
        // is fast, or silently races a slow page when it isn't.
        waitVisible(resultsList);
    }

    // ── Reads / assertions support ──────────────────────────────────────
    // Page objects return data or booleans for the TEST to assert on — a
    // page object should never itself call Assert.*. Keeping assertions out
    // of page objects is what lets the same page object get reused across a
    // regression test, a smoke test, and a keyword-driven CSV step without
    // dragging test-specific pass/fail logic along with it.

    public int getResultCount() {
        // driver.findElements(...) (plural, no wait) is the right call when
        // you need a count and zero matches is a valid, non-error outcome —
        // waitVisible/waitClickable are for when you need exactly one
        // element and zero would mean something's actually wrong.
        return driver.findElements(resultItem).size();
    }

    public String getFirstResultText() {
        return getText(resultItem); // getText() (from BasePage) already waits + trims
    }

    public boolean isSearchButtonVisible() {
        return isDisplayed(searchButton); // isDisplayed() (from BasePage) never throws — safe to call speculatively
    }
}
