package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class ToolTipsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard    = By.xpath("//h5[text()='Widgets']");
    private final By toolTipsMenu   = By.xpath("//span[text()='Tool Tips']");

    // ── Elements to hover ──────────────────────────────────────────────────────
    private final By hoverButton    = By.id("toolTipButton");
    private final By hoverTextField = By.id("toolTipTextField");

    // ── Tooltip text ───────────────────────────────────────────────────────────
    private final By toolTipText    = By.cssSelector(".tooltip-inner");

    public ToolTipsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToToolTips() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, toolTipsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(hoverButton));
    }

    /**
     * Hovers over the button and returns tooltip text.
     * Uses Actions class because tooltip appears on mouse hover,
     * not on click.
     */
    public String getButtonTooltipText() {
        WebElement button = wait.until(
                ExpectedConditions.visibilityOfElementLocated(hoverButton)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", button
        );
        HumanActions.pause();

        new Actions(driver).moveToElement(button).perform();

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(toolTipText)
        ).getText().trim();
    }

    public String getTextFieldTooltipText() {
        WebElement field = wait.until(
                ExpectedConditions.visibilityOfElementLocated(hoverTextField)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", field
        );
        HumanActions.pause();

        new Actions(driver).moveToElement(field).perform();

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(toolTipText)
        ).getText().trim();
    }
}