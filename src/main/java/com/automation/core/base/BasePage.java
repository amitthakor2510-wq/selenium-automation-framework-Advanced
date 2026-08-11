package com.automation.core.base;

import com.automation.core.config.ConfigReader;
import com.automation.core.selfhealing.SelfHealingEngine;
import com.automation.core.utils.DebugDumpUtils;
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
     * Thin delegate to {@link DebugDumpUtils}, the actual shared
     * implementation (moved there from here per docs/roadmap.md's tracked
     * tech-debt item — see that class's javadoc for the full history: this
     * was originally 3 separate copy-pasted implementations in
     * BookStoreApplicationPage/CheckBoxPage/ProfilePage before an earlier
     * pass consolidated them here, and this pass finishes that promotion
     * into core/utils, matching every other shared driver-facing helper in
     * this project). Kept as a protected method here — not just deleted in
     * favor of callers using DebugDumpUtils directly — so every existing
     * page-object call site (dumpPageForDebugging("...")) keeps working
     * unqualified and inherited, with no per-call-site edits required.
     */
    protected void dumpPageForDebugging(String label) {
        DebugDumpUtils.dumpPageForDebugging(driver, getClass(), label);
    }
}
