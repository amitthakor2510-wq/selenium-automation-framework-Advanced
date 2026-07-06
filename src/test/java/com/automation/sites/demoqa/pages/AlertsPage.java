package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class AlertsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By alertsMenu      = By.xpath("//span[text()='Alerts']");

    // ── Alert buttons ──────────────────────────────────────────────────────────
    private final By alertButton        = By.id("alertButton");
    private final By timerAlertButton   = By.id("timerAlertButton");
    private final By confirmButton      = By.id("confirmButton");
    private final By promtButton        = By.id("promtButton");

    // ── Result text ────────────────────────────────────────────────────────────
    private final By confirmResult      = By.id("confirmResult");
    private final By promptResult       = By.id("promptResult");

    public AlertsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToAlerts() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, alertsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(alertButton));
    }

    // ── Simple alert ───────────────────────────────────────────────────────────

    /**
     * Clicks the alert button.
     * Waits for the JS alert to appear.
     * Gets the alert text.
     * Accepts (clicks OK) the alert.
     */
    public String clickAlertAndGetText() {
        HumanActions.click(driver, alertButton);

        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();

        String text = alert.getText();
        alert.accept();

        return text;
    }

    // ── Timer alert ────────────────────────────────────────────────────────────

    /**
     * Clicks the timer alert button.
     * Alert appears after 5 seconds.
     * Uses a longer wait to handle the delay.
     */
    public String clickTimerAlertAndGetText() {
        HumanActions.click(driver, timerAlertButton);

        // Wait up to 10 seconds for alert to appear
        WebDriverWait longWait = new WebDriverWait(driver, Duration.ofSeconds(10));
        Alert alert = longWait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();

        String text = alert.getText();
        alert.accept();

        return text;
    }

    // ── Confirm alert ──────────────────────────────────────────────────────────

    /**
     * Clicks confirm button.
     * Alert appears with OK and Cancel.
     * Accepts (OK) and returns the result text shown on page.
     */
    public String clickConfirmAndAccept() {
        HumanActions.click(driver, confirmButton);

        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.accept();

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(confirmResult)
        ).getText();
    }

    /**
     * Clicks confirm button.
     * Dismisses (Cancel) and returns the result text shown on page.
     */
    public String clickConfirmAndDismiss() {
        HumanActions.click(driver, confirmButton);

        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.dismiss();

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(confirmResult)
        ).getText();
    }

    // ── Prompt alert ───────────────────────────────────────────────────────────

    /**
     * Clicks prompt button.
     * Alert appears with a text input box.
     * Types the given text into the prompt.
     * Accepts and returns the result text shown on page.
     */
    public String clickPromptAndEnterText(String text) {
        HumanActions.click(driver, promtButton);

        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();

        alert.sendKeys(text);
        HumanActions.pause();
        alert.accept();

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(promptResult)
        ).getText();
    }
}