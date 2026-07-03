package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class ButtonsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard  = By.xpath("//h5[text()='Elements']");
    private final By buttonsMenu   = By.xpath("//span[text()='Buttons']");

    // ── The three buttons on the page ──────────────────────────────────────────
    private final By doubleClickBtn  = By.id("doubleClickBtn");
    private final By rightClickBtn   = By.id("rightClickBtn");
    // The "Click Me" button has no stable ID - located by text
    private final By dynamicClickBtn = By.xpath("//button[text()='Click Me']");

    // ── Result messages that appear after each action ──────────────────────────
    private final By doubleClickMsg  = By.id("doubleClickMessage");
    private final By rightClickMsg   = By.id("rightClickMessage");
    private final By dynamicClickMsg = By.id("dynamicClickMessage");

    public ButtonsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToButtons() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, buttonsMenu);
        // Wait until at least one button is visible before returning
        wait.until(ExpectedConditions.visibilityOfElementLocated(doubleClickBtn));
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    /**
     * Performs a double-click using Selenium's Actions class.
     * Actions class is needed because a normal .click() only sends a single click.
     */
    public void performDoubleClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(doubleClickBtn));
        HumanActions.pause(); // human-like pause before the action
        new Actions(driver).doubleClick(btn).perform();
        HumanActions.pause();
    }

    /**
     * Performs a right-click (context click) using Selenium's Actions class.
     * contextClick() simulates pressing the right mouse button.
     */
    public void performRightClick() {
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(rightClickBtn));
        HumanActions.pause();
        new Actions(driver).contextClick(btn).perform();
        HumanActions.pause();
    }

    /**
     * Performs a normal left click.
     * HumanActions.click() handles the wait + pause for us.
     */
    public void performDynamicClick() {
        HumanActions.click(driver, dynamicClickBtn);
        HumanActions.pause();
    }

    // ── Result getters ─────────────────────────────────────────────────────────

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