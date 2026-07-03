package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DynamicPropertiesPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // longer wait because buttons take 5 seconds to change
    private final WebDriverWait longWait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard          = By.xpath("//h5[text()='Elements']");
    private final By dynamicPropertiesMenu = By.xpath("//span[text()='Dynamic Properties']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By enableAfterBtn  = By.id("enableAfter");
    private final By colorChangeBtn  = By.id("colorChange");
    private final By visibleAfterBtn = By.id("visibleAfter");

    public DynamicPropertiesPage(WebDriver driver) {
        this.driver   = driver;
        this.wait     = new WebDriverWait(driver, Duration.ofSeconds(10));

        // 10 seconds is enough for the 5 second delay + buffer
        this.longWait = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToDynamicProperties() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, dynamicPropertiesMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(enableAfterBtn));
    }

    // ── Enable After ───────────────────────────────────────────────────────────

    /**
     * The button starts as disabled.
     * After 5 seconds it becomes enabled/clickable.
     * We wait until it is clickable and return true if it becomes enabled.
     */
    public boolean isEnableAfterButtonEnabled() {
        try {
            longWait.until(ExpectedConditions.elementToBeClickable(enableAfterBtn));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Color Change ───────────────────────────────────────────────────────────

    /**
     * The button starts with no color class.
     * After 5 seconds its CSS class changes to include "text-danger"
     * which makes it turn red.
     * We wait until that class appears on the element.
     */
    public boolean hasColorChanged() {
        try {
            longWait.until(ExpectedConditions.attributeContains(
                    colorChangeBtn, "class", "text-danger"
            ));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the full class attribute so the test
     * can verify exactly what changed.
     */
    public String getColorButtonClass() {
        WebElement btn = wait.until(
                ExpectedConditions.presenceOfElementLocated(colorChangeBtn)
        );
        HumanActions.pause();
        return btn.getAttribute("class");
    }

    // ── Visible After ──────────────────────────────────────────────────────────

    /**
     * The button does not exist in the DOM at all when the page loads.
     * After 5 seconds it appears.
     * We wait until it becomes visible.
     */
    public boolean isVisibleAfterButtonDisplayed() {
        try {
            longWait.until(
                    ExpectedConditions.visibilityOfElementLocated(visibleAfterBtn)
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}