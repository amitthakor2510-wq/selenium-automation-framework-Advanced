package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MenuPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(MenuPage.class);

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard = By.xpath("//h5[text()='Widgets']");
    private final By menuItem    = By.xpath("//span[text()='Menu']");

    // ── Menu items ─────────────────────────────────────────────────────────────
    private final By mainItem1   = By.xpath("//a[normalize-space()='Main Item 1']");
    private final By mainItem2   = By.xpath("//a[normalize-space()='Main Item 2']");
    private final By subItem     = By.xpath("//a[normalize-space()='Sub Item']");
    private final By subList     = By.xpath("//a[normalize-space()='SUB SUB LIST »']");
    private final By subSubItem1 = By.xpath("//a[normalize-space()='Sub Sub Item 1']");

    public MenuPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToMenu() {
        navigateTo("/menu");
        wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem1));
        HumanActions.pause();
    }

    public String getMainItem1Text() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem1))
            .getText().trim();
    }

    public void hoverMainItem2() {
        WebElement item = wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem2));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", item);
        HumanActions.pause();
        // See HumanActions.hover() javadoc — plain Actions.moveToElement()
        // was flaking specifically under headless Chrome in CI.
        HumanActions.hover(driver, item);
        HumanActions.pause();
    }

    public boolean isSubItemVisible() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(subItem)).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * BUG FIX (confirmed against a live Jenkins run — MenuTest#verifySubSubItemOnHover
     * failed even after HumanActions.hover()'s real-Actions-move + JS-dispatch fix
     * resolved the single-level #verifySubItemOnHover case on the same page): the
     * nested flyout is one level deeper, so revealing it depends on the mouse
     * staying within Main Item 2's hover chain all the way to the "SUB SUB LIST »"
     * item, THEN staying within that item's hover chain long enough for the
     * flyout's own reveal to register — twice the opportunity for the same
     * single-attempt flakiness HumanActions.hover()'s javadoc describes for
     * headless Chrome. Retrying the whole hover sequence from the top (not just
     * re-hovering the inner item) matches DraggablePage's dragWithRetry()
     * pattern for the same class of first-attempt-flaky widget, and re-hovering
     * Main Item 2 first on each attempt guards against its hover state having
     * been lost partway through a previous attempt.
     */
    private static final int MAX_HOVER_ATTEMPTS = 3;

    public void hoverToSubSubList() {
        for (int attempt = 1; attempt <= MAX_HOVER_ATTEMPTS; attempt++) {
            WebElement item2 = wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem2));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", item2);
            HumanActions.pause();

            HumanActions.hover(driver, item2);
            HumanActions.microPause();

            WebElement subListEl;
            try {
                subListEl = wait.until(ExpectedConditions.visibilityOfElementLocated(subList));
            } catch (Exception e) {
                logger.warn("[MenuPage] Nested hover attempt " + attempt + "/" + MAX_HOVER_ATTEMPTS
                    + " — Sub List never appeared after hovering Main Item 2 — retrying");
                continue;
            }

            HumanActions.hover(driver, subListEl);
            HumanActions.microPause();
            HumanActions.pause();

            if (isSubSubItem1Visible()) {
                return;
            }
            logger.warn("[MenuPage] Nested hover attempt " + attempt + "/" + MAX_HOVER_ATTEMPTS
                + " did not reveal Sub Sub Item 1 — retrying");
        }
    }

    public boolean isSubSubItem1Visible() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(subSubItem1)).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}
