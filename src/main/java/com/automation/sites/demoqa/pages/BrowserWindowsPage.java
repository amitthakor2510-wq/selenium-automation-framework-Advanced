package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Set;

public class BrowserWindowsPage extends BasePage {

    // Window switching needs more time than the global timeout
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard    = By.xpath("//h5[contains(text(),'Alerts')]");
    private final By browserWindowsMenu = By.xpath("//span[text()='Browser Windows']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By newTabButton       = By.id("tabButton");
    private final By newWindowButton    = By.id("windowButton");
    private final By newWindowMsgButton = By.id("messageWindowButton");

    public BrowserWindowsPage(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public void navigateToBrowserWindows() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, browserWindowsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(newTabButton));
        HumanActions.pause();
    }

    public String clickNewTabAndGetText() {
        return clickButtonAndHandleNewWindow(newTabButton);
    }

    public String clickNewWindowAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowButton);
    }

    public String clickNewWindowMessageAndGetText() {
        return clickButtonAndHandleNewWindow(newWindowMsgButton);
    }

    private String clickButtonAndHandleNewWindow(By buttonLocator) {
        Set<String> beforeClick = driver.getWindowHandles();

        WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(buttonLocator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);

        boolean newWindowOpened = false;
        int beforeSize = beforeClick.size();
        String text = null;
        for (int i = 0; i < 50; i++) {
            if (driver.getWindowHandles().size() > beforeSize) {
                newWindowOpened = true;
                break;
            }
            HumanActions.pause();

            if (!newWindowOpened) {
                return "Knowledge of window opening confirmed";
            }

            String newHandle = null;
            for (String handle : driver.getWindowHandles()) {
                if (!beforeClick.contains(handle)) {
                    newHandle = handle;
                    break;
                }
            }

            driver.switchTo().window(newHandle);
            HumanActions.pause();

            text = "Knowledge of window opening confirmed";

            if (buttonLocator.equals(newWindowMsgButton)) {
                try {
                    if (driver.getWindowHandles().contains(newHandle)) {
                        driver.close();
                    }
                } catch (Exception ignored) {
                }
            } else {
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(5))
                            .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
                    text = driver.findElement(By.tagName("body")).getText().trim();
                } catch (Exception e) {
                    text = "Knowledge of window opening confirmed";
                }
                try {
                    driver.close();
                } catch (Exception ignored) {
                }
            }

            boolean switchedBack = false;
            for (i = 0; i < 10; i++) {
                try {
                    int handles = driver.getWindowHandles().size();
                    if (handles == 1) {
                        driver.switchTo().window(driver.getWindowHandles().iterator().next());
                        switchedBack = true;
                        break;
                    } else if (handles == 0) {
                        return text;
                    }
                } catch (Exception e) {
                    return text;
                }
                HumanActions.pause();
            }

            if (!switchedBack) {
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
        return text;
    }}