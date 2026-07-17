package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class ButtonsPage extends BasePage {

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By doubleClickBtn  = By.id("doubleClickBtn");
    private final By rightClickBtn   = By.id("rightClickBtn");
    private final By dynamicClickBtn = By.xpath("//button[text()='Click Me']");

    // ── Result messages ────────────────────────────────────────────────────────
    private final By doubleClickMsg  = By.id("doubleClickMessage");
    private final By rightClickMsg   = By.id("rightClickMessage");
    private final By dynamicClickMsg = By.id("dynamicClickMessage");

    public ButtonsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToButtons() {
        navigateTo("/buttons");
        wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickBtn));
        HumanActions.pause();
    }

    public void performDoubleClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(doubleClickBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        new Actions(driver).doubleClick(btn).perform();
        HumanActions.pause();
    }

    public void performRightClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(rightClickBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        new Actions(driver).contextClick(btn).perform();
        HumanActions.pause();
    }

    public void performDynamicClick() {
        // Dynamic click button can be intercepted by ads — scroll and JS click
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(dynamicClickBtn));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
        HumanActions.pause();
    }

    public String getDoubleClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickMsg)).getText();
    }

    public String getRightClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(rightClickMsg)).getText();
    }

    public String getDynamicClickMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(dynamicClickMsg)).getText();
    }
}
