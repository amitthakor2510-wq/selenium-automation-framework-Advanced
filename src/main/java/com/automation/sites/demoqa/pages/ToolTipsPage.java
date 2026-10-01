package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolTipsPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(ToolTipsPage.class);

    // BUG FIX (confirmed against a live Jenkins run — verifyButtonTooltip and
    // verifyTextFieldTooltip both timed out waiting 20s for .tooltip-inner,
    // even with HumanActions.hover()'s belt-and-braces JS event dispatch
    // already in place): Bootstrap's tooltip plugin only binds its
    // mouseenter/mouseleave listeners once per page load, and on a small
    // fraction of runs (headless Chrome, this suite's -Dheadless=true in
    // Jenkins/CI) that initial bind loses the race against our own
    // navigate-then-hover sequence — the hover events land before the
    // plugin has attached, so nothing shows. Same class of flakiness
    // BrokenLinksImagesPage.isValidImageLoaded() already retries around for
    // a similarly timing-sensitive third-party page. A fresh page load
    // re-runs Bootstrap's own init and gives the listener a second chance
    // to be bound before the next hover attempt.
    private static final int MAX_HOVER_ATTEMPTS = 3;

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
        return hoverAndGetTooltipText(hoverButton, "button");
    }

    public String getTextFieldTooltipText() {
        return hoverAndGetTooltipText(hoverTextField, "text field");
    }

    private String hoverAndGetTooltipText(By targetLocator, String label) {
        for (int attempt = 1; attempt <= MAX_HOVER_ATTEMPTS; attempt++) {
            WebElement target = wait.until(ExpectedConditions.visibilityOfElementLocated(targetLocator));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", target);
            highlightAndHover(target);
            try {
                return wait.until(
                    ExpectedConditions.visibilityOfElementLocated(toolTipText)
                ).getText().trim();
            } catch (TimeoutException te) {
                logger.info("Tooltip for " + label + " did not appear (attempt " + attempt + "/" + MAX_HOVER_ATTEMPTS + ")");
                if (attempt == MAX_HOVER_ATTEMPTS) {
                    throw te;
                }
                // Fresh navigation re-runs Bootstrap's own tooltip init —
                // see MAX_HOVER_ATTEMPTS javadoc above — instead of retrying
                // the hover against a page whose listener may never bind.
                navigateToToolTips();
            }
        }
        // Unreachable: the loop above always either returns or throws.
        throw new IllegalStateException("Unreachable");
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
        // See HumanActions.hover() javadoc — Bootstrap's tooltip plugin
        // reacts to a real mouseenter/mouseover JS event, and a bare
        // Actions.moveToElement() was flaking specifically under headless
        // Chrome in CI (Jenkins runs this suite with -Dheadless=true).
        HumanActions.hover(driver, element);
        try {
            Thread.sleep(800);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
