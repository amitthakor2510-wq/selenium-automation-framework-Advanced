package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

public class WebTablesPage extends BasePage {

    // ── Navigation ──────────────────────────────────────────────────────────────
    private final By addButton       = By.id("addNewRecordButton");
    private final By searchBox       = By.id("searchBox");

    // ── Registration form (modal) ───────────────────────────────────────────────
    private final By firstNameInput  = By.id("firstName");
    private final By lastNameInput   = By.id("lastName");
    private final By emailInput      = By.id("userEmail");
    private final By ageInput        = By.id("age");
    private final By salaryInput     = By.id("salary");
    private final By departmentInput = By.id("department");
    private final By submitButton    = By.id("submit");

    // ── Table rows ──────────────────────────────────────────────────────────────
    private final By tableRows     = By.cssSelector(".rt-tr-group");
    private final By deleteButtons = By.cssSelector("span[title='Delete']");
    private final By editButtons   = By.cssSelector("span[title='Edit']");

    public WebTablesPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    /** Called by WebTablesCRUDTest as page.openPage() */
    public void openPage() {
        navigateTo("/webtables");
        wait.until(ExpectedConditions.visibilityOfElementLocated(addButton));
    }

    /** Alias kept for any other tests that use the longer name */
    public void navigateToWebTables() {
        navigateTo("/webtables");
        wait.until(ExpectedConditions.visibilityOfElementLocated(addButton));
    }

    // ── Add record ──────────────────────────────────────────────────────────────

    public void clickAddButton() {
        HumanActions.click(driver, addButton);
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
    }

    /**
     * Fills and submits the registration modal.
     * Called by WebTablesCRUDTest as page.addRecord(...)
     */
    public void addRecord(String firstName, String lastName, String email,
                          String age, String salary, String department) {
        HumanActions.type(driver, firstNameInput,  firstName);
        HumanActions.type(driver, lastNameInput,   lastName);
        HumanActions.type(driver, emailInput,      email);
        HumanActions.type(driver, ageInput,        age);
        HumanActions.type(driver, salaryInput,     salary);
        HumanActions.type(driver, departmentInput, department);
        HumanActions.click(driver, submitButton);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(submitButton));
    }

    /** Original name kept as alias */
    public void fillRegistrationForm(String firstName, String lastName,
                                     String email, String age,
                                     String salary, String department) {
        addRecord(firstName, lastName, email, age, salary, department);
    }

    public void submitForm() {
        HumanActions.click(driver, submitButton);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(submitButton));
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    public void searchRecord(String keyword) {
        WebElement box = wait.until(
                ExpectedConditions.visibilityOfElementLocated(searchBox));
        box.clear();
        HumanActions.type(driver, searchBox, keyword);
    }

    // ── Read / assert ───────────────────────────────────────────────────────────

    public int getRowCount() {
        return (int) driver.findElements(tableRows).stream()
                .filter(row -> !row.getText().trim().isEmpty())
                .count();
    }

    /**
     * Returns true if any visible table row contains the given text.
     * Called by WebTablesCRUDTest as page.isRecordPresent(...)
     */
    public boolean isRecordPresent(String text) {
        List<WebElement> rows = driver.findElements(tableRows);
        return rows.stream()
                .anyMatch(row -> {
                    try {
                        return row.getText().trim().contains(text);
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    // ── Update ──────────────────────────────────────────────────────────────────

    /**
     * Finds the first row containing {@code searchText}, clicks its Edit button,
     * clears the department field and types {@code newDepartment}.
     * Called by WebTablesCRUDTest as page.updateRecord(...)
     */
    public void updateRecord(String searchText, String newDepartment) {
        List<WebElement> rows = driver.findElements(tableRows);
        for (WebElement row : rows) {
            try {
                if (!row.getText().trim().contains(searchText)) continue;

                WebElement editBtn = row.findElement(By.cssSelector("span[title='Edit']"));
                js.executeScript("arguments[0].scrollIntoView({block:'center'});", editBtn);
                js.executeScript("arguments[0].click();", editBtn);

                // Wait for modal to open
                wait.until(ExpectedConditions.visibilityOfElementLocated(departmentInput));

                // Clear and update department field
                WebElement deptField = driver.findElement(departmentInput);
                deptField.clear();
                HumanActions.typeHumanLike(deptField, newDepartment);

                HumanActions.click(driver, submitButton);
                wait.until(ExpectedConditions.invisibilityOfElementLocated(submitButton));
                return;
            } catch (Exception e) {
                // row may be stale or empty — skip
            }
        }
        throw new RuntimeException("No row found containing: " + searchText);
    }

    public void editFirstRecord() {
        WebElement editBtn = wait.until(
                ExpectedConditions.elementToBeClickable(editButtons));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", editBtn);
        js.executeScript("arguments[0].click();", editBtn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
    }

    // ── Delete ──────────────────────────────────────────────────────────────────

    /**
     * Finds the first row containing {@code searchText} and clicks its Delete button.
     * Called by WebTablesCRUDTest as page.deleteRecord(...)
     */
    public void deleteRecord(String searchText) {
        List<WebElement> rows = driver.findElements(tableRows);
        for (WebElement row : rows) {
            try {
                if (!row.getText().trim().contains(searchText)) continue;

                WebElement deleteBtn = row.findElement(By.cssSelector("span[title='Delete']"));
                js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteBtn);
                js.executeScript("arguments[0].click();", deleteBtn);
                HumanActions.pause();
                return;
            } catch (Exception e) {
                // row may be stale or empty — skip
            }
        }
        throw new RuntimeException("No row found containing: " + searchText);
    }

    public void deleteFirstRecord() {
        WebElement deleteBtn = wait.until(
                ExpectedConditions.elementToBeClickable(deleteButtons));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteBtn);
        js.executeScript("arguments[0].click();", deleteBtn);
        HumanActions.pause();
    }
}