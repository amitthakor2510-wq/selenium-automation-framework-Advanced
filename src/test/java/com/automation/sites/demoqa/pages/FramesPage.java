package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class FramesPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By framesMenu      = By.xpath("//span[text()='Frames']");

    // ── Frame locators ─────────────────────────────────────────────────────────
    private final By frame1          = By.id("frame1");
    private final By frame2          = By.id("frame2");

    // ── Text inside frame ──────────────────────────────────────────────────────
    private final By frameHeading    = By.id("sampleHeading");

    public FramesPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToFrames() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, framesMenu);
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(frame1));

        // Switch back to main page after confirming frame loaded
        driver.switchTo().defaultContent();
    }

    // ── Frame 1 ────────────────────────────────────────────────────────────────

    /**
     * Switches into frame1.
     * Reads the heading text inside it.
     * Switches back to main page.
     */
    public String getFrame1Text() {
        driver.switchTo().frame(driver.findElement(frame1));
        HumanActions.pause();

        String text = wait.until(
                ExpectedConditions.visibilityOfElementLocated(frameHeading)
        ).getText();

        driver.switchTo().defaultContent();
        return text;
    }

    // ── Frame 2 ────────────────────────────────────────────────────────────────

    public String getFrame2Text() {
        driver.switchTo().frame(driver.findElement(frame2));
        HumanActions.pause();

        String text = wait.until(
                ExpectedConditions.visibilityOfElementLocated(frameHeading)
        ).getText();

        driver.switchTo().defaultContent();
        return text;
    }
}