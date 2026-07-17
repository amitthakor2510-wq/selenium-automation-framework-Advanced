package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

public class ToolTipsPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard  = By.xpath("//h5[text()='Widgets']");
    private final By toolTipsMenu = By.xpath("//span[text()='Tool Tips']");

    // ── Elements to hover ──────────────────────────────────────────────────────
    private final By hoverButton    = By.id("toolTipButton");
    private final By hoverTextField = By.id("toolTipTextField");

    // ── Tooltip text ───────────────────────────────────────────────────────────
    private final By toolTipText = By.cssSelector(".tooltip-inner");

    public ToolTipsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToToolTips() {
        navigateTo("/tool-tips");
        wait.until(ExpectedConditions.visibilityOfElementLocated(hoverButton));
        HumanActions.pause();
    }

    public String getButtonTooltipText() {
        WebElement button = wait.until(
                ExpectedConditions.visibilityOfElementLocated(hoverButton)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", button);
        highlightAndHover(button);
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(toolTipText)
        ).getText().trim();
    }

    public String getTextFieldTooltipText() {
        WebElement field = wait.until(
                ExpectedConditions.visibilityOfElementLocated(hoverTextField)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", field);
        highlightAndHover(field);
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(toolTipText)
        ).getText().trim();
    }

    private void highlightAndHover(WebElement element) {
        // Show red outline on target element
        js.executeScript(
                "arguments[0].style.outline = '3px solid red';", element
        );
        // Show a visible red dot at the hover position
        js.executeScript(
                "var r = arguments[0].getBoundingClientRect();" +
                        "var x = r.left + r.width/2, y = r.top + r.height/2;" +
                        "var dot = document.createElement('div');" +
                        "dot.style.cssText = 'position:fixed;width:14px;height:14px;" +
                        "border-radius:50%;background:red;border:2px solid white;" +
                        "z-index:99999;pointer-events:none;" +
                        "left:'+(x-7)+'px;top:'+(y-7)+'px;';" +
                        "document.body.appendChild(dot);" +
                        "setTimeout(()=>{ dot.remove(); arguments[0].style.outline=''; }, 2000);",
                element
        );
        HumanActions.pause();
        new Actions(driver).moveToElement(element).pause(Duration.ofMillis(800)).perform();
    }
}