package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class ButtonsPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(ButtonsPage.class);

    // Actions.doubleClick()/contextClick() occasionally fire against demoqa's
    // Buttons page without the underlying JS click handler ever registering —
    // same class of first-attempt-missed-input flakiness DraggablePage's
    // dragWithRetry() was built for (see that class's javadoc), just for
    // native double/right click instead of drag. Re-locating the button and
    // retrying (verifying the result message actually showed up, rather than
    // trusting the Actions call alone) is the same fix, applied here.
    private static final int MAX_CLICK_ATTEMPTS = 3;
    private static final Duration PER_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By doubleClickBtn  = By.id("doubleClickBtn");
    private final By rightClickBtn   = By.id("rightClickBtn");
    private final By dynamicClickBtn = By.xpath("//button[text()='Click Me']");

    // ── Result messages ────────────────────────────────────────────────────────
    private final By doubleClickMsg  = By.id("doubleClickMessage");
    private final By rightClickMsg   = By.id("rightClickMessage");
    private final By dynamicClickMsg = By.id("dynamicClickMessage");

    public ButtonsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToButtons() {
        navigateTo("/buttons");
        wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickBtn));
        HumanActions.pause();
    }

    /**
     * Performs the given Actions-based click against the button located by
     * {@code buttonLocator}, then waits (with a short, per-attempt timeout)
     * for {@code resultLocator} to become visible — retrying the click itself
     * up to {@link #MAX_CLICK_ATTEMPTS} times, re-locating the button fresh
     * each attempt, if the result never shows up. Logs a warning on every
     * retry so a genuine app/locator regression (every attempt failing) is
     * still visible in the test output instead of just timing out once with
     * no context on whether it was a one-off input miss or a real break.
     */
    private void clickWithRetry(By buttonLocator, By resultLocator, String actionName) {
        WebDriverWait shortWait = new WebDriverWait(driver, PER_ATTEMPT_TIMEOUT);

        for (int attempt = 1; attempt <= MAX_CLICK_ATTEMPTS; attempt++) {
            WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(buttonLocator));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
            HumanActions.pause();

            Actions actions = new Actions(driver);
            if ("doubleClick".equals(actionName)) {
                actions.doubleClick(btn).perform();
            } else {
                actions.contextClick(btn).perform();
            }
            HumanActions.pause();

            if (attempt == MAX_CLICK_ATTEMPTS) {
                // Last attempt: let the real exception (with the full wait
                // chain/timeout) propagate on failure instead of swallowing
                // it — a genuine app/locator regression must still fail
                // loudly, not pass silently after exhausting retries.
                wait.until(ExpectedConditions.visibilityOfElementLocated(resultLocator));
                return;
            }

            try {
                shortWait.until(ExpectedConditions.visibilityOfElementLocated(resultLocator));
                return;
            } catch (TimeoutException e) {
                logger.warn("[ButtonsPage] {} attempt {}/{} produced no visible result ({}) — retrying",
                    actionName, attempt, MAX_CLICK_ATTEMPTS, resultLocator);
            }
        }
    }

    public void performDoubleClick() {
        clickWithRetry(doubleClickBtn, doubleClickMsg, "doubleClick");
    }

    public void performRightClick() {
        clickWithRetry(rightClickBtn, rightClickMsg, "contextClick");
    }

    public void performDynamicClick() {
        // Dynamic click button can be intercepted by ads — scroll and JS click
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(dynamicClickBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        HumanActions.pause();
    }

    public String getDoubleClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickMsg)).getText();
    }

    public String getRightClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(rightClickMsg)).getText();
    }

    public String getDynamicClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(dynamicClickMsg)).getText();
    }
}
