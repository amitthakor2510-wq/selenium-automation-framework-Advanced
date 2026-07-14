package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class ButtonsPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By buttonsMenu  = By.xpath("//span[text()='Buttons']");

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
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, buttonsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickBtn));
    }

    public void performDoubleClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(doubleClickBtn));
        HumanActions.pause();
        new Actions(driver).doubleClick(btn).perform();
        HumanActions.pause();
    }

    public void performRightClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(rightClickBtn));
        HumanActions.pause();
        new Actions(driver).contextClick(btn).perform();
        HumanActions.pause();
    }

    public void performDynamicClick() {
        HumanActions.click(driver, dynamicClickBtn);
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