package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class RadioButtonPage extends BasePage {

    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By radioButtonMenu = By.xpath("//span[text()='Radio Button']");
    private final By yesRadioLabel = By.xpath("//label[@for='yesRadio']");
    private final By resultText = By.className("text-success");

    public RadioButtonPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToRadioButton() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, radioButtonMenu);
    }

    public void selectYes() {
        HumanActions.click(driver, yesRadioLabel);
    }

    public String getResultText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(resultText)).getText();
    }
}
