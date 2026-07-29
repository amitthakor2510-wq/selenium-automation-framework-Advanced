package com.automation.core.base;

import com.automation.core.config.ConfigReader;
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
        String baseUrl = ConfigReader.get("url");
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

    protected WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    protected WebElement waitClickable(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    protected String getText(By locator) {
        return waitVisible(locator).getText().trim();
    }

    protected boolean isDisplayed(By locator) {
        return ElementUtils.isDisplayed(driver, locator);
    }
}
