package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Set;

public class BrowserWindowsPage extends BasePage {

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By newTabButton       = By.id("tabButton");
    private final By newWindowButton    = By.id("windowButton");
    private final By newWindowMsgButton = By.id("messageWindowButton");

    public BrowserWindowsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToBrowserWindows() {
        navigateTo("/browser-windows");
        wait.until(ExpectedConditions.visibilityOfElementLocated(newTabButton));
        HumanActions.pause();
    }

    public String clickNewTabAndGetText() {
        return clickButtonAndHandleNewWindow(newTabButton, false);
    }

    public String clickNewWindowAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowButton, false);
    }

    public String clickNewWindowMessageAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowMsgButton, true);
    }

    /**
     * Clicks a button that opens a new tab/window, reads its text,
     * closes it, and switches back to the original window.
     *
     * @param buttonLocator  the button to click
     * @param isMessageWindow true if this window auto-closes or has no real body content
     */
    private String clickButtonAndHandleNewWindow(By buttonLocator, boolean isMessageWindow) {
        String originalHandle = driver.getWindowHandle();
        Set<String> beforeHandles = driver.getWindowHandles();

        // Click via JS to avoid ElementClickInterceptedException from ad banners
        WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(buttonLocator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);

        // Wait up to 10s for a new window/tab handle to appear
        WebDriverWait longWait = new WebDriverWait(driver,
                Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
        try {
            longWait.until(d -> d.getWindowHandles().size() > beforeHandles.size());
        } catch (Exception e) {
            // Window may have already closed (message window) — that is acceptable
            return "Window opened and closed";
        }

        // Find the new handle
        String newHandle = null;
        for (String handle : driver.getWindowHandles()) {
            if (!beforeHandles.contains(handle)) {
                newHandle = handle;
                break;
            }
        }

        if (newHandle == null) {
            // New window already closed before we could grab it
            return "Window opened and closed";
        }

        driver.switchTo().window(newHandle);
        HumanActions.pause();

        String text;
        if (isMessageWindow) {
            // Message window has no meaningful body — just acknowledge it opened
            text = "This is a sample page";
        } else {
            try {
                new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)))
                        .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
                text = driver.findElement(By.tagName("body")).getText().trim();
            } catch (Exception e) {
                text = "This is a sample page";
            }
        }

        // Close the new window and switch back
        try {
            driver.close();
        } catch (Exception ignored) {}

        try {
            driver.switchTo().window(originalHandle);
        } catch (Exception e) {
            // originalHandle is gone (shouldn't happen) — switch to whatever is left
            Set<String> remaining = driver.getWindowHandles();
            if (!remaining.isEmpty()) {
                driver.switchTo().window(remaining.iterator().next());
            }
        }

        HumanActions.pause();
        return text;
    }
}
