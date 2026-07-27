package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class ProgressBarPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard      = By.xpath("//h5[text()='Widgets']");
    private final By progressBarMenu  = By.xpath("//span[text()='Progress Bar']");

    // ── Controls ───────────────────────────────────────────────────────────────
    private final By startStopButton  = By.id("startStopButton");
    private final By resetButton      = By.id("resetButton");
    private final By progressBar      = By.cssSelector(".progress-bar");

    public ProgressBarPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToProgressBar() {
        navigateTo("/progress-bar");
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

    /**
     * Starts the progress bar then stops it when value
     * reaches or passes the target percentage.
     * Uses a polling loop checking every 100ms.
     *
     * Note: We assert a range (±3) not exact value
     * because the bar increments continuously and
     * Selenium may not click stop at the exact ms.
     *
     * IMPORTANT: The stop click deliberately bypasses HumanActions.click()
     * because that method sleeps 400-1200ms before clicking (human-like delay).
     * During that sleep the bar keeps running and overshoots the target,
     * causing the assertion to fail. Direct click fires instantly.
     */
    public void startAndStopAtValue(int targetPercent) {
        // Start the bar (human-like click is fine here)
        HumanActions.click(driver, startStopButton);

        // Poll every 100ms until value reaches target
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            String current = driver.findElement(progressBar)
                .getAttribute("aria-valuenow");
            int currentValue = Integer.parseInt(current);

            if (currentValue >= targetPercent) {
                // Fire stop immediately — no human pause delay here
                driver.findElement(startStopButton).click();
                break;
            }

            HumanActions.pause();
        }
    }
}
