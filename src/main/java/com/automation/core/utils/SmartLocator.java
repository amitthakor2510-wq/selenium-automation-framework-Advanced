package com.automation.core.utils;

import com.automation.core.selfhealing.SelfHealingEngine;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Tries a primary locator first, then falls back to one or more alternates
 * before giving up — absorbs the class of breakage this framework has hit
 * repeatedly (e.g. demoqa's Check Box widget silently swapping
 * react-checkbox-tree for rc-tree), without needing a hotfix commit every
 * time a third-party site tweaks its markup.
 *
 * This is deliberately NOT a replacement for BasePage's normal
 * waitVisible()/waitClickable() — reach for it specifically on locators
 * that have already broken once, or that target a third-party page you
 * don't control and expect to keep changing under you.
 *
 * As of the self-healing work, the primary locator is itself resolved
 * through {@link SelfHealingEngine}, so a broken primary locator is
 * already given a chance to heal by DOM similarity before SmartLocator
 * ever falls through to an explicit fallback. Each explicit fallback gets
 * the same treatment. In other words: automatic similarity-based healing
 * runs first against the primary locator (cheap — it's just the engine
 * doing what it always does), and hand-written fallbacks remain the
 * deliberate, human-picked safety net for cases healing can't cover (a
 * genuinely different widget being swapped in, not just moved/restyled).
 *
 * Usage (inside a Page Object, which already extends BasePage and so has
 * `driver`/`wait` available):
 *
 *   private final By monthSelectPrimary  = By.className("react-datepicker__month-select");
 *   private final By monthSelectFallback = By.cssSelector("select[aria-label='Month']");
 *
 *   WebElement monthDropdown = SmartLocator.find(driver, wait,
 *       "DatePicker month <select>", monthSelectPrimary, monthSelectFallback);
 *
 * Each fallback attempt uses a short (2s) wait rather than the page's full
 * timeout, so a genuinely-missing element still fails at roughly the same
 * total time as a single locator would — you're not stacking full timeouts
 * on top of each other.
 */
public final class SmartLocator {

    private static final Logger logger = Logger.getLogger(SmartLocator.class.getName());
    private static final Duration FALLBACK_WAIT = Duration.ofSeconds(2);

    private SmartLocator() {
    }

    /** Finds a VISIBLE element, trying {@code primary} first and then each fallback in order. */
    public static WebElement find(WebDriver driver, WebDriverWait wait, String description,
                                  By primary, By... fallbacks) {
        return resolve(driver, wait, description, false, primary, fallbacks);
    }

    /** Same as {@link #find}, but waits for the element to be clickable instead of merely visible. */
    public static WebElement findClickable(WebDriver driver, WebDriverWait wait, String description,
                                           By primary, By... fallbacks) {
        return resolve(driver, wait, description, true, primary, fallbacks);
    }

    private static WebElement resolve(WebDriver driver, WebDriverWait wait, String description,
                                      boolean clickable, By primary, By... fallbacks) {
        List<By> candidates = new ArrayList<>();
        candidates.add(primary);
        if (fallbacks != null) {
            candidates.addAll(List.of(fallbacks));
        }

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            By locator = candidates.get(i);
            try {
                WebElement element = (i == 0)
                    ? locate(driver, wait, locator, clickable)
                    : locate(driver, new WebDriverWait(driver, FALLBACK_WAIT), locator, clickable);

                if (i > 0) {
                    // Not a failure — but worth knowing about, since it means the
                    // primary locator has drifted and the framework quietly
                    // absorbed it instead of failing the build outright.
                    logger.warning("[SmartLocator] '" + description + "' — primary locator "
                        + primary + " did not match; recovered using fallback #" + i + ": " + locator);
                }
                return element;
            } catch (Exception e) {
                failures.add(locator + " -> " + e.getClass().getSimpleName());
            }
        }

        throw new NoSuchElementException("[SmartLocator] '" + description
            + "' — none of " + candidates.size() + " locator(s) matched (each already given a chance to"
            + " self-heal by DOM similarity first). Tried: " + failures);
    }

    private static WebElement locate(WebDriver driver, WebDriverWait wait, By locator, boolean clickable) {
        return clickable
            ? SelfHealingEngine.findClickable(driver, wait, locator)
            : SelfHealingEngine.find(driver, wait, locator);
    }
}
