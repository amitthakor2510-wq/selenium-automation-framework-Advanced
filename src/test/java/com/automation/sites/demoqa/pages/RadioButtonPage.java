package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class RadioButtonPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By radioButtonMenu = By.xpath("//span[text()='Radio Button']");
    private final By yesRadioLabel = By.xpath("//label[@for='yesRadio']");
    private final By resultText = By.className("text-success");

    public RadioButtonPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(10));
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
