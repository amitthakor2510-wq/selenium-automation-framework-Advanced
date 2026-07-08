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

public class MenuPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

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
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToMenu() {
        // Step 1 - scroll to top first so widgets card is visible
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();

        // Step 2 - click Widgets card using JS to bypass ad banner
        WebElement widgets = wait.until(
                ExpectedConditions.presenceOfElementLocated(widgetsCard)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", widgets
        );
        HumanActions.pause();
        js.executeScript("arguments[0].click();", widgets);
        HumanActions.pause();

        // Step 3 - click Menu menu item using JS
        WebElement menu = wait.until(
                ExpectedConditions.presenceOfElementLocated(menuItem)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", menu
        );
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        // Step 4 - wait for menu items to load
        wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem1));

        // Step 5 - scroll menu into center of screen
        WebElement item1 = driver.findElement(mainItem1);
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", item1
        );
        HumanActions.pause();
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    public String getMainItem1Text() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(mainItem1)
        ).getText().trim();
    }

    public void hoverMainItem2() {
        WebElement item = wait.until(
                ExpectedConditions.visibilityOfElementLocated(mainItem2)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", item
        );
        HumanActions.pause();

        new Actions(driver)
                .moveToElement(item)
                .pause(Duration.ofMillis(800))
                .perform();

        HumanActions.pause();
    }

    public boolean isSubItemVisible() {
        try {
            return wait.until(
                    ExpectedConditions.visibilityOfElementLocated(subItem)
            ).isDisplayed();
        } catch (Exception e) {
            System.out.println("[MenuPage] Sub item not visible: "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * Fixed nested hover:
     * Chain all movements in ONE Actions sequence.
     * This keeps the mouse button held while moving
     * so the CSS :hover state stays active throughout.
     *
     * Separate perform() calls release the mouse between moves
     * which causes the first menu to close before second opens.
     */
    public void hoverToSubSubList() {
        WebElement item2 = wait.until(
                ExpectedConditions.visibilityOfElementLocated(mainItem2)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", item2
        );
        HumanActions.pause();

        // Move to Main Item 2 and wait for sub menu to appear
        new Actions(driver)
                .moveToElement(item2)
                .pause(Duration.ofMillis(1000))
                .perform();

        // Now move to SUB SUB LIST in same chain
        WebElement subListEl = wait.until(
                ExpectedConditions.visibilityOfElementLocated(subList)
        );

        new Actions(driver)
                .moveToElement(subListEl)
                .pause(Duration.ofMillis(1000))
                .perform();

        HumanActions.pause();
    }

    public boolean isSubSubItem1Visible() {
        try {
            return wait.until(
                    ExpectedConditions.visibilityOfElementLocated(subSubItem1)
            ).isDisplayed();
        } catch (Exception e) {
            System.out.println("[MenuPage] Sub sub item not visible: "
                    + e.getMessage());
            return false;
        }
    }
}