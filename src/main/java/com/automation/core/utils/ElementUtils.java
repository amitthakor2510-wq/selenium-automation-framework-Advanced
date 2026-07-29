package com.automation.core.utils;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;
import java.util.logging.Logger;

/**
 * Shared element-state helpers used by both BasePage and KeywordEngine.
 * Previously each had its own copy of isDisplayed(), and both did two
 * separate DOM queries — driver.findElements(locator).isEmpty() followed
 * by a second driver.findElement(locator).isDisplayed() call. An element
 * present for the first query could be gone by the second (e.g. it
 * re-rendered), throwing NoSuchElementException/StaleElementReferenceException
 * that got silently swallowed as "not displayed" — indistinguishable from
 * a genuinely dead WebDriver session, which also got silently swallowed
 * the same way.
 *
 * This version does a single query and only swallows the exceptions that
 * actually mean "not there" — anything else (a crashed session, etc.)
 * propagates instead of being reported as a false negative.
 */
public final class ElementUtils {

    private static final Logger logger = Logger.getLogger(ElementUtils.class.getName());

    private ElementUtils() {
    }

    /** True if the locator resolves to at least one element that is currently displayed. */
    public static boolean isDisplayed(WebDriver driver, By locator) {
        try {
            List<WebElement> elements = driver.findElements(locator);
            return !elements.isEmpty() && elements.get(0).isDisplayed();
        } catch (NoSuchElementException | StaleElementReferenceException e) {
            // Element genuinely isn't there (or re-rendered mid-check) — not displayed.
            return false;
        } catch (Exception e) {
            // Anything else (e.g. a crashed/dead session) is a real failure,
            // not "element not displayed" — surface it instead of hiding it.
            logger.warning("[ElementUtils] isDisplayed check failed unexpectedly for "
                + locator + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            throw e;
        }
    }
}
