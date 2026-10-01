package com.automation.mobile.core;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.CaptchaSolver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Mobile counterpart to core.base.BasePage — same shape (constructor takes
 * the driver, exposes a shared `wait`, thin waitVisible/waitClickable
 * helpers) so switching between writing a web Page Object and a mobile
 * screen object feels identical. Extend this for every app screen under
 * com.automation.mobile.sites.<app>.pages.
 *
 * Also mirrors BasePage's automatic CAPTCHA handling: RemoteWebDriver (what
 * AppiumDriverFactory hands every mobile screen object) implements the same
 * WebDriver/TakesScreenshot contract CaptchaSolver uses, so the exact same
 * detection/OCR logic works unmodified against a hybrid WebView captcha
 * rendered inside a mobile app — see handleCaptchaIfPresent().
 */
public abstract class BaseMobilePage {

    private static final Logger logger = LoggerFactory.getLogger(BaseMobilePage.class);

    protected final RemoteWebDriver driver;
    protected final WebDriverWait wait;

    // Lazily created — see BasePage's identical field for the full reasoning.
    private CaptchaSolver captchaSolver;

    protected BaseMobilePage(RemoteWebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigReader.getInt("mobile.timeout", 15)));
    }

    /**
     * Automatic CAPTCHA handling for mobile screen objects. Unlike web's
     * BasePage.navigateTo(), a mobile screen doesn't "navigate" the same
     * way (the app is already launched by AppiumDriverFactory before any
     * screen object is constructed), so this isn't auto-invoked from the
     * constructor — call it explicitly right after a screen transition
     * that could plausibly show a CAPTCHA (e.g. after a login submit).
     * Same no-op-when-absent, never-throws contract as BasePage's version;
     * see CaptchaSolver.autoSolveIfPresent() for exactly what it detects.
     * Disable with captcha.autoDetect.enabled=false.
     */
    protected void handleCaptchaIfPresent() {
        if (!ConfigReader.getBoolean("captcha.autoDetect.enabled", true)) {
            return;
        }
        try {
            if (CaptchaSolver.detectCaptchaImage(driver).isPresent()) {
                captchaSolver().autoSolveIfPresent(driver);
            }
        } catch (Exception e) {
            logger.warn("[BaseMobilePage] Auto CAPTCHA detection/solve failed (non-fatal): {}", e.getMessage());
        }
    }

    private CaptchaSolver captchaSolver() {
        if (captchaSolver == null) {
            captchaSolver = new CaptchaSolver();
        }
        return captchaSolver;
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
