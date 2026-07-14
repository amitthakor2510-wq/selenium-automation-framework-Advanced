package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class NestedFramesPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard   = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By nestedFramesMenu  = By.xpath("//span[text()='Nested Frames']");

    // ── Frames ─────────────────────────────────────────────────────────────────
    private final By parentFrame       = By.id("frame1");
    private final By childFrame        = By.tagName("iframe");

    public NestedFramesPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToNestedFrames() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, nestedFramesMenu);
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(parentFrame));
        driver.switchTo().defaultContent();
    }

    // ── Parent frame ───────────────────────────────────────────────────────────

    /**
     * Switches into the parent frame.
     * Reads the text directly inside it.
     * Switches back to main page.
     */
    public String getParentFrameText() {
        driver.switchTo().frame(driver.findElement(parentFrame));
        HumanActions.pause();

        String text = driver.findElement(By.tagName("body")).getText();

        driver.switchTo().defaultContent();
        return text;
    }

    // ── Child frame ────────────────────────────────────────────────────────────

    /**
     * Switches into parent frame first.
     * Then switches into child frame inside it.
     * Reads the text inside child frame.
     * Switches all the way back to main page.
     *
     * This shows the key concept - to reach child frame
     * you must go through parent frame first.
     * You cannot jump directly to child from main page.
     */
    public String getChildFrameText() {
        // Step 1 - enter parent frame
        driver.switchTo().frame(driver.findElement(parentFrame));
        HumanActions.pause();

        // Step 2 - enter child frame inside parent
        driver.switchTo().frame(driver.findElement(childFrame));
        HumanActions.pause();

        String text = driver.findElement(By.tagName("body")).getText();

        // Step 3 - go all the way back to main page
        driver.switchTo().defaultContent();

        return text;
    }
}