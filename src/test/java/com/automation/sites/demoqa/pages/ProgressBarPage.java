package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class ProgressBarPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard      = By.xpath("//h5[text()='Widgets']");
    private final By progressBarMenu  = By.xpath("//span[text()='Progress Bar']");

    // ── Controls ───────────────────────────────────────────────────────────────
    private final By startStopButton  = By.id("startStopButton");
    private final By resetButton      = By.id("resetButton");
    private final By progressBar      = By.cssSelector(".progress-bar");

    public ProgressBarPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToProgressBar() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, progressBarMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(startStopButton));
    }

    public void clickStartStop() {
        HumanActions.click(driver, startStopButton);
    }

    /**
     * Starts the progress bar, waits until it reaches
     * 100% then stops it.
     */
    public void waitForCompletion() {
        HumanActions.click(driver, startStopButton);

        // Wait up to 15 seconds for progress to reach 100%
        wait.until(d ->
                d.findElement(progressBar)
                        .getAttribute("aria-valuenow")
                        .equals("100")
        );
    }

    public String getProgressValue() {
        return driver.findElement(progressBar)
                .getAttribute("aria-valuenow");
    }

    public void clickReset() {
        HumanActions.click(driver, resetButton);
        HumanActions.pause();
    }
}