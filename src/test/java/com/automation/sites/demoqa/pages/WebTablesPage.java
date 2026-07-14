package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class WebTablesPage extends BasePage {

    // ── Navigation ──────────────────────────────────────────────────────────────
    private final By elementsCard  = By.xpath("//h5[text()='Elements']");
    private final By webTablesMenu = By.xpath("//span[text()='Web Tables']");

    // ── Table controls ──────────────────────────────────────────────────────────
    private final By addButton      = By.id("addNewRecordButton");
    private final By searchBox      = By.id("searchBox");

    // ── Registration form ───────────────────────────────────────────────────────
    private final By firstNameInput = By.id("firstName");
    private final By lastNameInput  = By.id("lastName");
    private final By emailInput     = By.id("userEmail");
    private final By ageInput       = By.id("age");
    private final By salaryInput    = By.id("salary");
    private final By departmentInput= By.id("department");
    private final By submitButton   = By.id("submit");

    // ── Table rows ──────────────────────────────────────────────────────────────
    private final By tableRows      = By.cssSelector(".rt-tr-group");
    private final By deleteButtons  = By.cssSelector("span[title='Delete']");
    private final By editButtons    = By.cssSelector("span[title='Edit']");

    public WebTablesPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToWebTables() {
        String baseUrl = ConfigReader.get("url");
        driver.get(baseUrl + "/webtables");
        wait.until(ExpectedConditions.visibilityOfElementLocated(addButton));
    }

    public void clickAddButton() {
        HumanActions.click(driver, addButton);
    }

    public void fillRegistrationForm(String firstName, String lastName,
                                     String email, String age,
                                     String salary, String department) {
        HumanActions.type(driver, firstNameInput, firstName);
        HumanActions.type(driver, lastNameInput, lastName);
        HumanActions.type(driver, emailInput, email);
        HumanActions.type(driver, ageInput, age);
        HumanActions.type(driver, salaryInput, salary);
        HumanActions.type(driver, departmentInput, department);
    }

    public void submitForm() {
        HumanActions.click(driver, submitButton);
    }

    public void searchRecord(String keyword) {
        HumanActions.type(driver, searchBox, keyword);
    }

    public int getRowCount() {
        return (int) driver.findElements(tableRows).stream()
                .filter(row -> !row.getText().trim().isEmpty())
                .count();
    }

    public void deleteFirstRecord() {
        WebElement deleteBtn = wait.until(
                ExpectedConditions.elementToBeClickable(deleteButtons));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteBtn);
        js.executeScript("arguments[0].click();", deleteBtn);
    }

    public void editFirstRecord() {
        WebElement editBtn = wait.until(
                ExpectedConditions.elementToBeClickable(editButtons));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", editBtn);
        js.executeScript("arguments[0].click();", editBtn);
    }
}