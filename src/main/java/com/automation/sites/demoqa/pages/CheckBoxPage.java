package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

public class CheckBoxPage extends BasePage {

    // Expand toggle (root node collapse/expand arrow)
    private final By expandToggle    = By.cssSelector(".rct-collapse.rct-collapse-btn");
    // Desktop node label (text-based, stable)
    private final By desktopLabel    = By.xpath("//span[@class='rct-title' and text()='Desktop']");
    // Result section
    private final By resultSection   = By.id("result");

    public CheckBoxPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToCheckBox() {
        navigateTo("/checkbox");
        wait.until(ExpectedConditions.visibilityOfElementLocated(expandToggle));
        HumanActions.pause();
    }

    public void expandTree() {
        WebElement toggle = wait.until(
                ExpectedConditions.elementToBeClickable(expandToggle));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", toggle);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", toggle);
        // Wait for Desktop node to appear after expanding
        wait.until(ExpectedConditions.visibilityOfElementLocated(desktopLabel));
        HumanActions.pause();
    }

    public void selectDesktop() {
        // Click the checkbox icon next to the Desktop label
        // The checkbox icon is the sibling span before the label span
        By desktopCheckbox = By.xpath(
                "//span[@class='rct-title' and text()='Desktop']" +
                "//ancestor::li[1]//span[contains(@class,'rct-checkbox')]"
        );
        WebElement cb = wait.until(
                ExpectedConditions.elementToBeClickable(desktopCheckbox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", cb);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", cb);
        HumanActions.pause();
    }

    public boolean isResultDisplayed() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(resultSection))
                .isDisplayed();
    }
}
