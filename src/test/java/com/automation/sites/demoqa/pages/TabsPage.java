package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class TabsPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard  = By.xpath("//h5[text()='Widgets']");
    private final By tabsMenu     = By.xpath("//span[text()='Tabs']");

    // ── Tabs ───────────────────────────────────────────────────────────────────
    private final By whatTab      = By.id("demo-tab-what");
    private final By originTab    = By.id("demo-tab-origin");
    private final By useTab       = By.id("demo-tab-use");

    // ── Tab content ────────────────────────────────────────────────────────────
    private final By whatContent   = By.id("demo-tabpane-what");
    private final By originContent = By.id("demo-tabpane-origin");
    private final By useContent    = By.id("demo-tabpane-use");

    public TabsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToTabs() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, tabsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(whatTab));
    }

    public String getWhatTabContent() {
        // What tab is active by default
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(whatContent)
        ).getText().trim();
    }

    public String getOriginTabContent() {
        WebElement tab = driver.findElement(originTab);
        js.executeScript("arguments[0].click();", tab);
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(originContent)
        ).getText().trim();
    }

    public String getUseTabContent() {
        WebElement tab = driver.findElement(useTab);
        js.executeScript("arguments[0].click();", tab);
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(useContent)
        ).getText().trim();
    }
}