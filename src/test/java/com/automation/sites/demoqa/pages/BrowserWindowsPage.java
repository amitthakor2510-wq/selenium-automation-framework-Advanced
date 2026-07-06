package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class BrowserWindowsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard    = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By browserWindowsMenu = By.xpath("//span[text()='Browser Windows']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By newTabButton       = By.id("tabButton");
    private final By newWindowButton    = By.id("windowButton");
    private final By newWindowMsgButton = By.id("messageWindowButton");

    public BrowserWindowsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToBrowserWindows() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, browserWindowsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(newTabButton));
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    /**
     * Clicks New Tab button.
     * Switches to the new tab.
     * Returns the text content of that tab.
     * Closes new tab and switches back.
     */
    public String clickNewTabAndGetText() {
        String originalHandle = driver.getWindowHandle();
        HumanActions.click(driver, newTabButton);
        return switchToNewWindowAndGetText(originalHandle);
    }

    /**
     * Clicks New Window button.
     * Switches to the new window.
     * Returns the text content of that window.
     * Closes new window and switches back.
     */
    public String clickNewWindowAndGetText() {
        String originalHandle = driver.getWindowHandle();
        HumanActions.click(driver, newWindowButton);
        return switchToNewWindowAndGetText(originalHandle);
    }

    /**
     * Clicks New Window Message button.
     * Switches to the new window.
     * Returns the body text shown in that window.
     * Closes new window and switches back.
     */
    public String clickNewWindowMessageAndGetText() {
        String originalHandle = driver.getWindowHandle();
        HumanActions.click(driver, newWindowMsgButton);
        return switchToNewWindowAndGetText(originalHandle);
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Waits for a new window/tab to open.
     * Switches to it.
     * Gets the page text.
     * Closes it.
     * Switches back to original window.
     */
    private String switchToNewWindowAndGetText(String originalHandle) {
        // Wait until second window/tab opens
        wait.until(ExpectedConditions.numberOfWindowsToBe(2));

        // Find the new window handle and switch to it
        for (String handle : driver.getWindowHandles()) {
            if (!handle.equals(originalHandle)) {
                driver.switchTo().window(handle);
                break;
            }
        }

        HumanActions.pause();

        // Get text from the new window
        String text = driver.findElement(By.tagName("body")).getText();

        // Close new window and go back
        driver.close();
        driver.switchTo().window(originalHandle);

        return text;
    }
}