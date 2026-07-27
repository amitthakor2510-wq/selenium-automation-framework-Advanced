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

    protected boolean isDisplayed(By locator) {
        try {
            return !driver.findElements(locator).isEmpty()
                && driver.findElement(locator).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}
