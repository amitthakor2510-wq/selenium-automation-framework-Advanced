package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class FramesPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By framesMenu      = By.xpath("//span[text()='Frames']");

    // ── Frame locators ─────────────────────────────────────────────────────────
    private final By frame1      = By.id("frame1");
    private final By frame2      = By.id("frame2");
    private final By frameHeading = By.id("sampleHeading");

    public FramesPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToFrames() {
        navigateTo("/frames");
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(frame1));
        driver.switchTo().defaultContent();
    }

    public String getFrame1Text() {
        driver.switchTo().frame(driver.findElement(frame1));
        HumanActions.pause();
        String text = wait.until(ExpectedConditions.visibilityOfElementLocated(frameHeading)).getText();
        driver.switchTo().defaultContent();
        return text;
    }

    public String getFrame2Text() {
        driver.switchTo().frame(driver.findElement(frame2));
        HumanActions.pause();
        String text = wait.until(ExpectedConditions.visibilityOfElementLocated(frameHeading)).getText();
        driver.switchTo().defaultContent();
        return text;
    }
}