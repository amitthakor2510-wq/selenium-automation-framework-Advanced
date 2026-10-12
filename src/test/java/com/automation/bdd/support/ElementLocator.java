package com.automation.bdd.support;

import com.automation.core.keyword.ObjectRepository;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

import java.util.Locale;

/**
 * Turns the text a tester writes in a feature file into a Selenium/Appium {@link By}.
 * Works for every site and app, with or without an object repository:
 *
 * <pre>
 *   "css:#login-button"            inline locator - nothing to set up
 *   "xpath://button[@type='submit']"
 *   "text:Sign in"                 any element showing this text
 *   "accessibility:Login"          mobile (Appium accessibility id)
 *   "login.username"               key from objectrepository/&lt;site&gt;.properties
 * </pre>
 *
 * Inline prefixes: id, name, css, xpath, class, linktext, partiallinktext, tag, text, and for
 * mobile accessibility, uiselector (Android UiSelector), predicate / classchain (iOS). Anything
 * without a known prefix is treated as an object-repository key; the site prefix may be left off
 * ("login.username" = "saucedemo.login.username").
 */
public final class ElementLocator {

    private ElementLocator() {
    }

    public static By resolve(String element, String site) {
        if (element == null || element.isBlank()) {
            throw new IllegalArgumentException("Element name is empty");
        }
        String trimmed = element.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();
            By inline = inline(prefix, value);
            if (inline != null) {
                return inline;
            }
        }
        return fromRepository(trimmed, site);
    }

    private static By inline(String prefix, String value) {
        return switch (prefix) {
            case "id" -> By.id(value);
            case "name" -> By.name(value);
            case "css" -> By.cssSelector(value);
            case "xpath" -> By.xpath(value);
            case "class" -> By.className(value);
            case "linktext" -> By.linkText(value);
            case "partiallinktext" -> By.partialLinkText(value);
            case "tag" -> By.tagName(value);
            case "text" -> By.xpath("//*[text()[contains(normalize-space(.), " + xpathLiteral(value) + ")]]");
            case "accessibility" -> AppiumBy.accessibilityId(value);
            case "uiselector" -> AppiumBy.androidUIAutomator(value);
            case "predicate" -> AppiumBy.iOSNsPredicateString(value);
            case "classchain" -> AppiumBy.iOSClassChain(value);
            default -> null;
        };
    }

    private static By fromRepository(String key, String site) {
        String resource = "objectrepository/" + site + ".properties";
        ObjectRepository repo;
        try {
            repo = ObjectRepository.load(resource);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + key + "' is not an inline locator (e.g. css:#id, "
                + "xpath://..., text:Sign in) and there is no object repository at " + resource
                + " to look it up in.", e);
        }
        String qualified = key.startsWith(site + ".") ? key : site + "." + key;
        try {
            return repo.get(qualified);
        } catch (RuntimeException first) {
            try {
                return repo.get(key);
            } catch (RuntimeException ignored) {
                throw first;   // its message lists the known keys
            }
        }
    }

    /** Quotes a string for use inside an XPath expression, handling both quote characters. */
    private static String xpathLiteral(String s) {
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append("'").append(parts[i]).append("'");
        }
        return sb.append(")").toString();
    }
}
