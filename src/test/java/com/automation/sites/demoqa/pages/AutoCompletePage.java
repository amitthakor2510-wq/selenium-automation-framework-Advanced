package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

public class AutoCompletePage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard      = By.xpath("//h5[text()='Widgets']");
    private final By autoCompleteMenu = By.xpath("//span[text()='Auto Complete']");

    // ── Multi color input ──────────────────────────────────────────────────────
    private final By multiInput  = By.id("autoCompleteMultipleInput");
    private final By multiValues = By.cssSelector(".auto-complete__multi-value__label");

    // ── Single color input ─────────────────────────────────────────────────────
    private final By singleInput = By.id("autoCompleteSingleInput");
    private final By singleValue = By.cssSelector(".auto-complete__single-value");

    // ── Dropdown options ───────────────────────────────────────────────────────
    private final By dropdownOption = By.cssSelector(".auto-complete__option");

    public AutoCompletePage(WebDriver driver) {
        super(driver);
    }

    public void navigateToAutoComplete() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, autoCompleteMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(multiInput));
    }

    public void typeMultiColor(String color) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(multiInput));
        HumanActions.pause();
        input.sendKeys(color);
        wait.until(ExpectedConditions.visibilityOfElementLocated(dropdownOption));
        HumanActions.pause();
        input.sendKeys(Keys.ENTER);
    }

    public List<WebElement> getMultiSelectedValues() {
        return driver.findElements(multiValues);
    }

    public int getMultiSelectedCount() {
        return getMultiSelectedValues().size();
    }

    public void typeSingleColor(String color) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(singleInput));
        HumanActions.pause();
        input.sendKeys(color);
        wait.until(ExpectedConditions.visibilityOfElementLocated(dropdownOption));
        HumanActions.pause();
        input.sendKeys(Keys.ENTER);
    }

    public String getSingleSelectedValue() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(singleValue))
                .getText().trim();
    }
}