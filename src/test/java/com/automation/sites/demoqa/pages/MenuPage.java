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
    private final By mainItem1   = By.xpath("//a[text()='Main Item 1']");
    private final By mainItem2   = By.xpath("//a[text()='Main Item 2']");
    private final By subItem     = By.xpath("//a[text()='Sub Item']");
    private final By subList     = By.xpath("//a[text()='SUB SUB LIST »']");
    private final By subSubItem1 = By.xpath("//a[text()='Sub Sub Item 1']");
    private final By subSubItem2 = By.xpath("//a[text()='Sub Sub Item 2']");

    public MenuPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToMenu() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, menuItem);
        wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem1));
    }

    public String getMainItem1Text() {
        return driver.findElement(mainItem1).getText();
    }

    /**
     * Hovers over Main Item 2 to reveal sub items.
     * Menu uses CSS hover — must use Actions.moveToElement.
     */
    public void hoverMainItem2() {
        WebElement item = wait.until(
                ExpectedConditions.visibilityOfElementLocated(mainItem2)
        );
        new Actions(driver).moveToElement(item).perform();
        HumanActions.pause();
    }

    public boolean isSubItemVisible() {
        try {
            return wait.until(
                    ExpectedConditions.visibilityOfElementLocated(subItem)
            ).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hover Main Item 2 → hover Sub List → Sub Sub items appear.
     */
    public void hoverToSubSubList() {
        hoverMainItem2();

        WebElement subListEl = wait.until(
                ExpectedConditions.visibilityOfElementLocated(subList)
        );
        new Actions(driver).moveToElement(subListEl).perform();
        HumanActions.pause();
    }

    public boolean isSubSubItem1Visible() {
        try {
            return wait.until(
                    ExpectedConditions.visibilityOfElementLocated(subSubItem1)
            ).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}