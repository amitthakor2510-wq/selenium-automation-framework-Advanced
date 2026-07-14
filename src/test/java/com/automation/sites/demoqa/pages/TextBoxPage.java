package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class TextBoxPage extends BasePage {

    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By textBoxMenu = By.xpath("//span[text()='Text Box']");
    private final By userName = By.id("userName");
    private final By userEmail = By.id("userEmail");
    private final By currentAddress = By.id("currentAddress");
    private final By permanentAddress = By.id("permanentAddress");
    private final By submitButton = By.id("submit");
    private final By outputName = By.id("name");

    public TextBoxPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToTextBox() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, textBoxMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(userName));
    }

    public void fillForm(String name, String email, String currAddr, String permAddr) {
        HumanActions.type(driver, userName, name);
        HumanActions.type(driver, userEmail, email);
        HumanActions.type(driver, currentAddress, currAddr);
        HumanActions.type(driver, permanentAddress, permAddr);
        HumanActions.click(driver, submitButton);
    }

    public String getOutputName() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(outputName)).getText();
    }
}
