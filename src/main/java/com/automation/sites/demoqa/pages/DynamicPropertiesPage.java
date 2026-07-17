package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DynamicPropertiesPage extends BasePage {

    // Longer wait because buttons take 5 seconds to change
    private final WebDriverWait longWait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard          = By.xpath("//h5[text()='Elements']");
    private final By dynamicPropertiesMenu = By.xpath("//span[text()='Dynamic Properties']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By enableAfterBtn  = By.id("enableAfter");
    private final By colorChangeBtn  = By.id("colorChange");
    private final By visibleAfterBtn = By.id("visibleAfter");

    public DynamicPropertiesPage(WebDriver driver) {
        super(driver);
        // 15 seconds: enough for the 5-second delay plus buffer
        this.longWait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public void navigateToDynamicProperties() {
        navigateTo("/dynamic-properties");
        wait.until(ExpectedConditions.visibilityOfElementLocated(enableAfterBtn));
    }

    public boolean isEnableAfterButtonEnabled() {
        try {
            longWait.until(ExpectedConditions.elementToBeClickable(enableAfterBtn));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasColorChanged() {
        try {
            longWait.until(ExpectedConditions.attributeContains(
                    colorChangeBtn, "class", "text-danger"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getColorButtonClass() {
        WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(colorChangeBtn));
        HumanActions.pause();
        return btn.getAttribute("class");
    }

    public boolean isVisibleAfterButtonDisplayed() {
        try {
            longWait.until(ExpectedConditions.visibilityOfElementLocated(visibleAfterBtn));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}