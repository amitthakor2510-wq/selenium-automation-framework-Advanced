package com.automation.core.utils;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(ElementUtils.class);

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
            logger.warn("[ElementUtils] isDisplayed check failed unexpectedly for "
                + locator + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            throw e;
        }
    }

    /**
     * Safely embeds an arbitrary string as an XPath 1.0 string literal.
     * <p>
     * Several Page Objects build locators like
     * {@code "//div[text()='" + value + "']"} by directly concatenating a
     * runtime value (a day number, a dropdown option, free-text test data)
     * into the expression. That works only as long as {@code value} never
     * contains a single quote — a value like {@code O'Brien} produces a
     * syntactically invalid XPath expression instead of a clear "not found"
     * result. XPath 1.0 has no escape character for quotes inside a
     * quoted literal, so the standard workaround is used here:
     * <ul>
     *   <li>no single quote in the value → wrap in {@code '...'}</li>
     *   <li>single quotes but no double quotes → wrap in {@code "..."}</li>
     *   <li>both present → split on {@code '} and rebuild with
     *       {@code concat(...)}, alternating single/double-quoted chunks</li>
     * </ul>
     * The returned string is the literal ready to drop straight into an
     * XPath expression, e.g. {@code "//div[text()=" + xpathLiteral(value) + "]"}.
     */
    public static String xpathLiteral(String value) {
        if (value == null) {
            return "''";
        }
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        if (!value.contains("\"")) {
            return "\"" + value + "\"";
        }
        String[] parts = value.split("'", -1);
        StringBuilder sb = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            sb.append("'").append(parts[i]).append("'");
            if (i < parts.length - 1) {
                sb.append(", \"'\", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
