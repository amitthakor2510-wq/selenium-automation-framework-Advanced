package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

public class MenuPage extends BasePage {

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
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();

        WebElement widgets = wait.until(ExpectedConditions.presenceOfElementLocated(widgetsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", widgets);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", widgets);
        HumanActions.pause();

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(menuItem));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem1));
        WebElement item1 = driver.findElement(mainItem1);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", item1);
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
        new Actions(driver).moveToElement(item).pause(Duration.ofMillis(800)).perform();
        HumanActions.pause();
    }

    public boolean isSubItemVisible() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(subItem)).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    public void hoverToSubSubList() {
        WebElement item2 = wait.until(ExpectedConditions.visibilityOfElementLocated(mainItem2));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", item2);
        HumanActions.pause();

        new Actions(driver).moveToElement(item2).pause(Duration.ofMillis(1000)).perform();

        WebElement subListEl = wait.until(ExpectedConditions.visibilityOfElementLocated(subList));
        new Actions(driver).moveToElement(subListEl).pause(Duration.ofMillis(1000)).perform();
        HumanActions.pause();
    }

    public boolean isSubSubItem1Visible() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(subSubItem1)).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}