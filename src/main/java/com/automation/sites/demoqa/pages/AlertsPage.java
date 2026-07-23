package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v125.page.Page;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.Optional;
import java.util.logging.Logger;

public class AlertsPage extends BasePage {

    private static final Logger logger = Logger.getLogger(AlertsPage.class.getName());

    private final By alertButton      = By.id("alertButton");
    private final By timerAlertButton = By.id("timerAlertButton");
    private final By confirmButton    = By.id("confirmButton");
    private final By promptButton     = By.id("promtButton");

    private final By confirmResult = By.id("confirmResult");
    private final By promtResult  = By.id("promptResult");

    public AlertsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToAlerts() {
        navigateTo("/alerts");
        wait.until(ExpectedConditions.visibilityOfElementLocated(alertButton));
        dismissAdOverlay();
        HumanActions.pause();
    }

    private void dismissAdOverlay() {
        try {
            js.executeScript(
                    "document.querySelectorAll(" +
                            "  '#fixedban, [id*=\"google_ads\"], iframe[src*=\"googlesyndication\"]," +
                            "  iframe[src*=\"doubleclick\"], div[id^=\"adngin\"]," +
                            "  div[class*=\"adsbygoogle\"]'" +
                            ").forEach(function(el){ el.remove(); });"
            );
        } catch (Exception ignored) {}
    }

    private void safeClick(By locator) {
        dismissAdOverlay();
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", el);
    }

    public String clickAlertAndGetText() {
        safeClick(alertButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        String text = alert.getText();
        alert.accept();
        return text;
    }

    public String clickTimerAlertAndGetText() {
        safeClick(timerAlertButton);
        Alert alert = new org.openqa.selenium.support.ui.WebDriverWait(driver,
                java.time.Duration.ofSeconds(
                        com.automation.core.config.ConfigReader.getInt("timeout.long", 15)))
                .until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        String text = alert.getText();
        alert.accept();
        return text;
    }

    public String clickConfirmAndAccept() {
        safeClick(confirmButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.accept();
        return wait.until(ExpectedConditions.visibilityOfElementLocated(confirmResult)).getText();
    }

    public String clickConfirmAndDismiss() {
        safeClick(confirmButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.dismiss();
        return wait.until(ExpectedConditions.visibilityOfElementLocated(confirmResult)).getText();
    }

    /**
     * The CDP command classes imported above (org.openqa.selenium.devtools.v125.*)
     * are generated for a specific Chrome DevTools Protocol version. Selenium
     * negotiates the "nearest available" version at runtime and only warns
     * (CdpVersionFinder) rather than failing outright, but once the actual
     * browser drifts far enough past what this Selenium release bundles,
     * that negotiation stops being reliable. Rather than always attempting
     * the CDP path and paying for an exception + warning on every single
     * run against a too-new browser, check the gap up front and go straight
     * to the proven-reliable Alert fallback when it's too wide.
     *
     * 130 is a deliberately conservative ceiling — comfortably above the
     * v125 command classes actually imported here, but low enough to catch
     * "the CI runner auto-updated Chrome way ahead of this Selenium version"
     * before it becomes a real failure instead of just a log warning. Revisit
     * this (or better, bump the Selenium/CDP dependency) if it starts
     * triggering on browser versions you know are still fine.
     */
    private boolean isChromeVersionLikelyCompatibleWithCdp() {
        try {
            if (driver instanceof HasCapabilities) {
                String browserVersion = ((HasCapabilities) driver).getCapabilities().getBrowserVersion();
                if (browserVersion != null && !browserVersion.isEmpty()) {
                    int majorVersion = Integer.parseInt(browserVersion.split("\\.")[0]);
                    return majorVersion <= 130;
                }
            }
        } catch (Exception e) {
            logger.warning("[AlertsPage] Could not determine browser version for CDP compatibility check: " + e.getMessage());
        }
        // Unknown version — default to attempting CDP; the existing try/catch
        // below still falls back safely if it turns out to be incompatible.
        return true;
    }

    /**
     * Handles the JS prompt() dialog using Chrome DevTools Protocol (CDP) to
     * inject text directly at the browser level, completely bypassing OS-level
     * input/focus (which Robot/xdotool/clipboard hacks all depend on and which
     * fails reliably in headless, CI, Wayland, or compositor-heavy environments).
     *
     * This is the ONLY cross-platform, deterministic way to populate a native
     * prompt() dialog in Chromium-based browsers. For non-Chromium (Firefox,
     * Safari), falls back to alert.sendKeys() which works there but is ignored
     * by some Chrome versions — hence the CDP-first strategy.
     */
    public String clickPromptAndEnterText(String text) {
        safeClick(promptButton);

        // Wait for the dialog to appear *before* trying to handle it via CDP
        wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();

        // Use CDP to handle the dialog with the provided text, then accept it.
        // This sends a Page.handleJavaScriptDialog command with accept=true
        // and promptText=<our text>, which Chrome processes internally without
        // requiring any OS-level focus/input. The dialog closes and the page
        // callback fires with the text we provided.
        if (driver instanceof HasDevTools && isChromeVersionLikelyCompatibleWithCdp()) {
            try {
                DevTools devTools = ((HasDevTools) driver).getDevTools();
                devTools.createSession();
                devTools.send(Page.handleJavaScriptDialog(true, Optional.of(text)));
                logger.info("[AlertsPage] Handled prompt via CDP with text: \"" + text + "\"");
            } catch (Exception e) {
                logger.warning("[AlertsPage] CDP handling failed, falling back to Alert.sendKeys: " + e.getMessage());
                Alert alert = driver.switchTo().alert();
                alert.sendKeys(text);
                alert.accept();
            }
        } else {
            // Non-Chromium browser (Firefox, Safari), or a Chrome version too
            // far ahead of this Selenium build's CDP support (see
            // isChromeVersionLikelyCompatibleWithCdp) — use standard WebDriver Alert.
            Alert alert = driver.switchTo().alert();
            alert.sendKeys(text);
            alert.accept();
        }

        HumanActions.pause();

        // Guard against stale DOM: wait for the result span to contain the
        // expected text before reading it, so we don't accidentally read a
        // leftover value from a previous test.
        wait.until(ExpectedConditions.textToBe(promtResult, "You entered " + text));
        return driver.findElement(promtResult).getText();
    }
}