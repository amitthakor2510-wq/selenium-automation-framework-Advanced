package com.automation.mobile.core;

import com.automation.core.config.ConfigReader;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Mobile counterpart to core.base.BasePage — same shape (constructor takes
 * the driver, exposes a shared `wait`, thin waitVisible/waitClickable
 * helpers) so switching between writing a web Page Object and a mobile
 * screen object feels identical. Extend this for every app screen under
 * com.automation.mobile.sites.<app>.pages.
 */
public abstract class BaseMobilePage {

    protected final RemoteWebDriver driver;
    protected final WebDriverWait wait;

    protected BaseMobilePage(RemoteWebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigReader.getInt("mobile.timeout", 15)));
    }

    protected WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    protected WebElement waitClickable(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    /**
     * Unlike the web BasePage's isDisplayed(), this polls for visibility within
     * the configured timeout instead of checking once. On web, isDisplayed() is
     * typically called after some prior explicit wait has already settled the
     * page onto a known state, so an instant check is fine there. Here it's the
     * only assertion right after driver session creation / app launch — with a
     * booting emulator, the app's UI can still be rendering when this check
     * would otherwise run once and fail, making the smoke test intermittently
     * flaky for reasons that have nothing to do with the app actually being
     * broken.
     */
    protected boolean isDisplayed(By locator) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(locator)) != null;
        } catch (org.openqa.selenium.TimeoutException e) {
            return false;
        }
    }
}
