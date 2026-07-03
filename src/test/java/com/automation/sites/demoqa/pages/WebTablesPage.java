package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class WebTablesPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    public WebTablesPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ---------------- LOCATORS ----------------

    private final By addButton = By.id("addNewRecordButton");

    private final By firstNameField = By.id("firstName");
    private final By lastNameField = By.id("lastName");
    private final By emailField = By.id("userEmail");
    private final By ageField = By.id("age");
    private final By salaryField = By.id("salary");
    private final By departmentField = By.id("department");
    private final By submitButton = By.id("submit");

    private final By searchBox = By.id("searchBox");

    // ---------------- PAGE ACTIONS ----------------

    public void openPage() {
        driver.get("https://demoqa.com/webtables");
        wait.until(ExpectedConditions.visibilityOfElementLocated(addButton));
    }

    public void clickAddButton() {
        HumanActions.click(driver, addButton);
    }

    public void addRecord(String firstName,
                          String lastName,
                          String email,
                          String age,
                          String salary,
                          String department) {

        HumanActions.type(driver, firstNameField, firstName);
        HumanActions.type(driver, lastNameField, lastName);
        HumanActions.type(driver, emailField, email);
        HumanActions.type(driver, ageField, age);
        HumanActions.type(driver, salaryField, salary);
        HumanActions.type(driver, departmentField, department);

        HumanActions.click(driver, submitButton);

        wait.until(ExpectedConditions.invisibilityOfElementLocated(submitButton));
    }

    public void searchRecord(String keyword) {
        WebElement search = wait.until(ExpectedConditions.visibilityOfElementLocated(searchBox));

        HumanActions.pause();
        search.clear();
        HumanActions.typeHumanLike(search, keyword);

        // Give the React table time to filter results
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- VALIDATION ----------------

    public boolean isRecordPresent(String value) {

        try {
            searchRecord(value);

            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                List<WebElement> cells = driver.findElements(By.cssSelector(".rt-td"));
                for (WebElement cell : cells) {
                    try {
                        if (cell.isDisplayed() && cell.getText().contains(value)) {
                            return true;
                        }
                    } catch (StaleElementReferenceException ignored) {
                    }
                }

                List<WebElement> matches = driver.findElements(By.xpath("//*[contains(normalize-space(.),'" + value + "')]"));
                for (WebElement el : matches) {
                    try {
                        if (!el.getTagName().equalsIgnoreCase("input") && el.isDisplayed()) {
                            return true;
                        }
                    } catch (StaleElementReferenceException ignored) {
                    }
                }

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- DELETE ----------------

    public void deleteRecord(String firstName) {
        searchRecord(firstName);

        List<WebElement> deleteButtons = driver.findElements(By.cssSelector("span[title='Delete']"));

        if (!deleteButtons.isEmpty()) {
            HumanActions.click(driver, deleteButtons.get(0));

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            throw new RuntimeException("Delete button not found for record: " + firstName);
        }
    }

    // ---------------- EDIT ----------------

    public void updateRecord(String firstName, String newDepartment) {
        searchRecord(firstName);

        List<WebElement> editButtons = driver.findElements(By.cssSelector("span[title='Edit']"));

        if (!editButtons.isEmpty()) {
            HumanActions.click(driver, editButtons.get(0));

            WebElement deptField = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.id("department"))
            );

            HumanActions.pause();
            deptField.clear();
            HumanActions.typeHumanLike(deptField, newDepartment);

            HumanActions.click(driver, By.id("submit"));

            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("submit")));
        } else {
            throw new RuntimeException("Edit button not found for record: " + firstName);
        }
    }
}
