package com.automation.core.base;

import com.automation.core.config.ConfigReader;
import com.automation.core.selfhealing.SelfHealingEngine;
import com.automation.core.utils.ElementUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public abstract class BasePage {

    protected final WebDriver driver;
    protected final WebDriverWait wait;
    protected final JavascriptExecutor js;

    protected BasePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
        this.js     = (JavascriptExecutor) driver;
    }

    /**
     * Navigates to a path relative to the site's base URL.
     * e.g. navigateTo("/webtables") → driver.get("https://demoqa.com/webtables")
     * e.g. navigateTo("")           → driver.get("https://demoqa.com")
     */
    protected void navigateTo(String path) {
        // Strip a trailing slash from the configured base URL so a config
        // file with e.g. url=https://demoqa.com/ can't produce a double
        // slash (https://demoqa.com//webtables) when concatenated below.
        String baseUrl = ConfigReader.get("url");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (path == null || path.isEmpty()) {
            driver.get(baseUrl);
        } else {
            driver.get(baseUrl + (path.startsWith("/") ? path : "/" + path));
        }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    protected void scrollAndJsClick(WebElement el) {
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        js.executeScript("arguments[0].click();", el);
    }

    protected void scrollAndJsClick(By locator) {
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        scrollAndJsClick(el);
    }

    /**
     * Routed through SelfHealingEngine: if {@code locator} no longer matches
     * anything (the page's markup drifted since this Page Object was
     * written), the engine re-finds the element by similarity against the
     * fingerprint captured the last time this exact locator succeeded,
     * rather than failing the test outright. See SelfHealingEngine's class
     * doc for the full mechanics; self-healing.enabled=false disables it.
     */
    protected WebElement waitVisible(By locator) {
        return SelfHealingEngine.find(driver, wait, locator);
    }

    protected WebElement waitClickable(By locator) {
        return SelfHealingEngine.findClickable(driver, wait, locator);
    }

    protected String getText(By locator) {
        return waitVisible(locator).getText().trim();
    }

    protected boolean isDisplayed(By locator) {
        return ElementUtils.isDisplayed(driver, locator);
    }

    /**
     * Writes the current page source to target/debug-dumps/ for offline
     * inspection when a page object hits an unexpected state (element not
     * found after a wait, URL not reached, etc.) — call this right before
     * throwing/failing so the dump captures the actual DOM at that moment.
     *
     * Moved here from 3 separate copy-pasted private implementations
     * (BookStoreApplicationPage, CheckBoxPage, ProfilePage) that had
     * drifted slightly — one used logger.info while the other two used
     * logger.fine for the identical situation, an inconsistency rather
     * than an intentional difference. Uses this page's own runtime class
     * for the logger name (Logger.getLogger(getClass().getName())) so log
     * output still reads as coming from e.g. CheckBoxPage, not BasePage.
     */
    protected void dumpPageForDebugging(String label) {
        java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(getClass().getName());
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("target", "debug-dumps");
            java.nio.file.Files.createDirectories(dir);
            String fileName = label.replaceAll("[^a-zA-Z0-9]", "")
                + "-" + System.currentTimeMillis() + ".html";
            java.nio.file.Path file = dir.resolve(fileName);
            java.nio.file.Files.writeString(file, driver.getPageSource());
            logger.fine("  DEBUG full page source written to: " + file.toAbsolutePath());
        } catch (Exception writeEx) {
            logger.fine("  DEBUG could not write page source dump: " + writeEx.getMessage());
        }
        logger.fine("  DEBUG current URL: " + driver.getCurrentUrl());
    }
}
