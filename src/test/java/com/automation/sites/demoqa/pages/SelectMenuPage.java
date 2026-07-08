package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class SelectMenuPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard      = By.xpath("//h5[text()='Widgets']");
    private final By selectMenuMenu   = By.xpath("//span[text()='Select Menu']");

    // ── Select Value (react-select) ────────────────────────────────────────────
    private final By selectValueInput = By.id("react-select-2-input");
    private final By selectValueDisplay = By.cssSelector(
            "#withOptGroup .css-1uccc91-singleValue, " +
                    "#withOptGroup .react-select__single-value"
    );

    // ── Select One (react-select) ──────────────────────────────────────────────
    private final By selectOneInput   = By.id("react-select-3-input");

    // ── Old style select ───────────────────────────────────────────────────────
    private final By oldStyleSelect   = By.id("oldSelectMenu");

    // ── Multi select ───────────────────────────────────────────────────────────
    private final By multiSelect      = By.id("react-select-4-input");

    // ── Standard multi select ──────────────────────────────────────────────────
    private final By standardMulti    = By.id("cars");

    public SelectMenuPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToSelectMenu() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, selectMenuMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(oldStyleSelect));
    }

    // ── Select Value ───────────────────────────────────────────────────────────

    public void selectValue(String value) {
        WebElement input = wait.until(
                ExpectedConditions.elementToBeClickable(selectValueInput)
        );
        HumanActions.pause();
        input.sendKeys(value);

        By option = By.xpath(
                "//div[contains(@class,'option') and contains(.,'" + value + "')]"
        );
        wait.until(ExpectedConditions.visibilityOfElementLocated(option));
        HumanActions.pause();
        input.sendKeys(Keys.ENTER);
    }

    // ── Old style select ───────────────────────────────────────────────────────

    public void selectOldStyleOption(String visibleText) {
        WebElement select = wait.until(
                ExpectedConditions.visibilityOfElementLocated(oldStyleSelect)
        );
        HumanActions.pause();
        new Select(select).selectByVisibleText(visibleText);
    }

    public String getOldStyleSelectedValue() {
        WebElement select = driver.findElement(oldStyleSelect);
        return new Select(select).getFirstSelectedOption().getText();
    }

    // ── Standard multi select ──────────────────────────────────────────────────

    public void selectCarOption(String visibleText) {
        WebElement select = wait.until(
                ExpectedConditions.visibilityOfElementLocated(standardMulti)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", select
        );
        HumanActions.pause();
        new Select(select).selectByVisibleText(visibleText);
    }

    public String getSelectedCarOption() {
        return new Select(driver.findElement(standardMulti))
                .getFirstSelectedOption().getText();
    }
}