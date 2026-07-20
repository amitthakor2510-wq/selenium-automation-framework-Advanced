package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class AlertsPage extends BasePage {

    // ── Alert buttons ──────────────────────────────────────────────────────────
    private final By alertButton      = By.id("alertButton");
    private final By timerAlertButton = By.id("timerAlertButton");
    private final By confirmButton    = By.id("confirmButton");
    private final By promptButton     = By.id("promptButton");

    // ── Result text ────────────────────────────────────────────────────────────
    private final By confirmResult = By.id("confirmResult");
    private final By promptResult  = By.id("promptResult");

    public AlertsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToAlerts() {
        navigateTo("/alerts");
        wait.until(ExpectedConditions.visibilityOfElementLocated(alertButton));
        HumanActions.pause();
    }

    public String clickAlertAndGetText() {
        HumanActions.click(driver, alertButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        String text = alert.getText();
        alert.accept();
        return text;
    }

    public String clickTimerAlertAndGetText() {
        HumanActions.click(driver, timerAlertButton);
        // Timer alert appears after 5 seconds — need a longer wait
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
        HumanActions.click(driver, confirmButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.accept();
        return wait.until(ExpectedConditions.visibilityOfElementLocated(confirmResult)).getText();
    }

    public String clickConfirmAndDismiss() {
        HumanActions.click(driver, confirmButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.dismiss();
        return wait.until(ExpectedConditions.visibilityOfElementLocated(confirmResult)).getText();
    }

    public String clickPromptAndEnterText(String text) {
        HumanActions.click(driver, promptButton);
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        HumanActions.pause();
        alert.sendKeys(text);
        HumanActions.pause();
        alert.accept();
        return wait.until(ExpectedConditions.visibilityOfElementLocated(promptResult)).getText();
    }
}
