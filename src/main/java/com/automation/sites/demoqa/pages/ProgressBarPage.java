package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
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
     * <p>
     * BUG FIX: the bar's aria-valuenow attribute is updated by a React
     * re-render on every tick, and WebDriverWait's default ignored-exception
     * list only covers NotFoundException (NoSuchElementException) — NOT
     * StaleElementReferenceException. If a re-render happens to swap the
     * element out between this lambda's findElement() and getAttribute()
     * calls (a real, if narrow, race — the same class of race every other
     * polling loop in this codebase explicitly guards against, e.g.
     * ProfilePage/WebTablesPage), the exception would propagate straight
     * out of wait.until() and fail the whole test instead of just being
     * retried on the next 500ms poll. Swallowing it here and returning
     * false (i.e. "not done yet, keep polling") is the correct, safe
     * response — it's exactly what the code already does for a
     * genuinely-missing element via the ignored NotFoundException.
     */
    public void waitForCompletion() {
        HumanActions.click(driver, startStopButton);

        // Wait up to 15 seconds for progress to reach 100%
        wait.until(d -> {
            try {
                return d.findElement(progressBar)
                    .getAttribute("aria-valuenow")
                    .equals("100");
            } catch (StaleElementReferenceException e) {
                return false;
            }
        });
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
     * <p>
     * BUG FIX: unlike waitForCompletion() above, this hand-rolled polling
     * loop had no protection at all against a StaleElementReferenceException
     * from the same React re-render race — a single unlucky poll would throw
     * straight out of this method and fail the whole test instead of just
     * being retried on the next 100ms iteration, exactly the gap just fixed
     * in waitForCompletion(). Wrapped the read in the same try/catch so one
     * stale read is simply skipped rather than aborting the whole poll.
     */
    public void startAndStopAtValue(int targetPercent) {
        // Start the bar (human-like click is fine here)
        HumanActions.click(driver, startStopButton);

        // Poll every 100ms until value reaches target
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            int currentValue;
            try {
                String current = driver.findElement(progressBar)
                    .getAttribute("aria-valuenow");
                currentValue = Integer.parseInt(current);
            } catch (StaleElementReferenceException e) {
                HumanActions.pause();
                continue;
            }

            if (currentValue >= targetPercent) {
                // Fire stop immediately — no human pause delay here
                try {
                    driver.findElement(startStopButton).click();
                } catch (StaleElementReferenceException e) {
                    driver.findElement(startStopButton).click();
                }
                break;
            }

            HumanActions.pause();
        }
    }
}
