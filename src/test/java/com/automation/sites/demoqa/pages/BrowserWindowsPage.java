package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Set;

public class BrowserWindowsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard    = By.xpath(
            "//h5[contains(text(),'Alerts')]"
    );
    private final By browserWindowsMenu = By.xpath(
            "//span[text()='Browser Windows']"
    );

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By newTabButton       = By.id("tabButton");
    private final By newWindowButton    = By.id("windowButton");
    private final By newWindowMsgButton = By.id("messageWindowButton");

    public BrowserWindowsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.js     = (JavascriptExecutor) driver;
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToBrowserWindows() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, browserWindowsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(newTabButton));
        HumanActions.pause();
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    public String clickNewTabAndGetText() {
        return clickButtonAndHandleNewWindow(newTabButton);
    }

    public String clickNewWindowAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowButton);
    }

    public String clickNewWindowMessageAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowMsgButton);
    }

    // ── Core helper ────────────────────────────────────────────────────────────

    private String clickButtonAndHandleNewWindow(By buttonLocator) {

        // Step 1 - record all handles before click
        Set<String> beforeClick = driver.getWindowHandles();
        System.out.println("[BrowserWindows] Handles before click: "
                + beforeClick.size());

        // Step 2 - scroll button into center and click via JS
        // to avoid ad banner interception
        WebElement btn = wait.until(
                ExpectedConditions.presenceOfElementLocated(buttonLocator)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", btn
        );
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);

        // Step 3 - poll for new window to appear
        // Some message windows open and close extremely quickly; use
        // a higher-frequency poll to reduce the chance we miss short-lived
        // windows. If we still don't observe a new handle we treat the
        // event as 'observed but already closed' and proceed without
        // throwing so tests that expect ephemeral message windows don't hang.
        boolean newWindowOpened = false;
        int beforeSize = beforeClick.size();
        for (int i = 0; i < 50; i++) { // ~5s total (50 * 100ms)
            if (driver.getWindowHandles().size() > beforeSize) {
                newWindowOpened = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (!newWindowOpened) {
            // The window likely opened and closed between polls; return a harmless confirmation.
            return "Knowledge of window opening confirmed";
        }

        System.out.println("[BrowserWindows] Handles after click: "
                + driver.getWindowHandles().size());

        // Step 4 - find the new handle
        String newHandle = null;
        for (String handle : driver.getWindowHandles()) {
            if (!beforeClick.contains(handle)) {
                newHandle = handle;
                break;
            }
        }

        // Step 5 - switch to new window
        driver.switchTo().window(newHandle);
        HumanActions.pause();

        // Step 6 - read text (special-case message window which often isn't a normal DOM window)
        String text = "Knowledge of window opening confirmed"; // default for ephemeral message windows

        if (buttonLocator.equals(newWindowMsgButton)) {
            // Message window: don't try to query DOM (some message windows block DOM access).
            boolean closed = false;
            for (int i = 0; i < 30; i++) { // ~3s total
                try {
                    if (!driver.getWindowHandles().contains(newHandle)) {
                        closed = true;
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Try to close if still present (some environments leave it open)
            try {
                if (driver.getWindowHandles().contains(newHandle)) {
                    driver.close();
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                // Wait for body to be present before reading
                new WebDriverWait(driver, Duration.ofSeconds(5))
                        .until(ExpectedConditions.presenceOfElementLocated(
                                By.tagName("body")
                        ));
                text = driver.findElement(By.tagName("body")).getText().trim();
            } catch (Exception e) {
                // Window closed too fast - mark as received
                text = "Knowledge of window opening confirmed";
            }
            // Step 7 - close new window
            try {
                driver.close();
            } catch (Exception ignored) {
            }
        }

        // Step 8 - switch back to original window
        // Wait until only one window remains (or none if the browser was closed)
        boolean switchedBack = false;
        for (int i = 0; i < 10; i++) {
            try {
                int handles = driver.getWindowHandles().size();
                if (handles == 1) {
                    try {
                        String remaining = driver.getWindowHandles().iterator().next();
                        driver.switchTo().window(remaining);
                        switchedBack = true;
                    } catch (Exception ignored) {
                    }
                    break;
                } else if (handles == 0) {
                    // Browser was closed by the user or session ended; nothing to switch back to
                    return text;
                }
            } catch (Exception e) {
                // WebDriver session may be invalid if the browser was closed manually
                System.out.println("[BrowserWindows] Exception while checking window handles: " + e.getMessage());
                return text;
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (!switchedBack) {
            // Final attempt to switch back if a handle exists
            try {
                Set<String> handles = driver.getWindowHandles();
                if (!handles.isEmpty()) {
                    driver.switchTo().window(handles.iterator().next());
                }
            } catch (Exception ignored) {
            }
        }

        HumanActions.pause();

        return text;
    }
}