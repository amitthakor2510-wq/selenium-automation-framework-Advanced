package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

public class CheckBoxPage extends BasePage {

    private final By elementsCard    = By.xpath("//h5[text()='Elements']");
    private final By checkBoxMenu    = By.xpath("//span[text()='Check Box']");
    private final By toggleButtons   = By.cssSelector(
            "#root > div > div > div > div.col-12.mt-4.col-md-6.col-xl-7 > " +
                    "div.check-box-tree-wrapper > div > div.rc-tree-list > div > div > " +
                    "div > div > span.rc-tree-switcher.rc-tree-switcher_close");
    private final By desktopCheckbox = By.xpath(
            "//*[@id=\"root\"]/div/div/div/div[2]/div[1]/div/div[3]/div/div/div/div[2]/span[3]");
    private final By resultSection   = By.id("result");

    public CheckBoxPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToCheckBox() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, checkBoxMenu);
    }

    public void expandTree() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(toggleButtons));
        List<WebElement> toggles = driver.findElements(toggleButtons);
        if (!toggles.isEmpty()) {
            HumanActions.pause();
            js.executeScript("arguments[0].click();", toggles.get(0));
        }
        wait.until(ExpectedConditions.visibilityOfElementLocated(desktopCheckbox));
    }

    public void selectDesktop() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(desktopCheckbox));
        HumanActions.pause();
        js.executeScript("arguments[0].click();", driver.findElement(desktopCheckbox));
    }

    public boolean isResultDisplayed() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(resultSection))
                .isDisplayed();
    }
}