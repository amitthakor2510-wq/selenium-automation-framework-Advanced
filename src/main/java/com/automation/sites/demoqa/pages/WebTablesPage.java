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
    // DemoQA's web-tables page now renders a plain semantic <table>/<tr>/<td>
    // instead of the old react-table div grid (".rt-tr-group" — confirmed gone
    // from the live DOM). Scoping to "table tbody tr" also naturally excludes
    // any header row. Edit/Delete selectors are unchanged — confirmed still
    // correct against the current markup (span[title='Edit'|'Delete']).
    private final By tableRows     = By.cssSelector("table tbody tr");
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
     * Clicks the modal's Submit button via scrollIntoView + JS click, same as
     * the Edit/Delete row buttons below.
     * <p>
     * Root cause of the CRUD tests failing 100% of the time with no thrown
     * exception: a plain HumanActions.click(driver, submitButton) resolves
     * the button's on-screen coordinates, but DemoQA's page keeps shifting
     * layout after that resolution (ad slots loading in, content reflowing),
     * so by the time Selenium dispatches the native click it can land on
     * whatever now sits at those old coordinates instead of the actual
     * button. The click "succeeds" (no ElementClickIntercepted exception),
     * the modal even appears to close, but the form was never truly
     * submitted, so the row never lands in the table. A JS click dispatches
     * the click event directly on the element node, sidestepping coordinates
     * and the overlay entirely — which is exactly why the Edit/Delete row
     * buttons already do it this way.
     */
    private void clickSubmit() {
        HumanActions.pause();
        WebElement submit = wait.until(ExpectedConditions.presenceOfElementLocated(submitButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", submit);
        js.executeScript("arguments[0].click();", submit);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(submitButton));
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
        clickSubmit();
    }

    /** Original name kept as alias */
    public void fillRegistrationForm(String firstName, String lastName,
                                     String email, String age,
                                     String salary, String department) {
        addRecord(firstName, lastName, email, age, salary, department);
    }

    public void submitForm() {
        clickSubmit();
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
     * <p>
     * Polls rather than checking the DOM once — the search box filters
     * the table live as React re-renders, and checking synchronously
     * right after typing can run before that re-render has happened,
     * producing a false negative that has nothing to do with whether
     * the record is actually there.
     */
    public boolean isRecordPresent(String text) {
        try {
            wait.until(d -> {
                List<WebElement> rows = d.findElements(tableRows);
                return rows.stream().anyMatch(row -> {
                    try {
                        return row.getText().trim().contains(text);
                    } catch (Exception e) {
                        return false;
                    }
                });
            });
            return true;
        } catch (org.openqa.selenium.TimeoutException e) {
            return false;
        }
    }

    // ── Update ──────────────────────────────────────────────────────────────────

    /**
     * Finds the first row containing {@code searchText}, clicks its Edit button,
     * clears the department field and types {@code newDepartment}.
     * Called by WebTablesCRUDTest as page.updateRecord(...)
     * <p>
     * Polls for the row + Edit button rather than taking a single
     * driver.findElements(tableRows) snapshot. The search box filters the
     * table asynchronously as React re-renders after the last keystroke, so
     * a snapshot taken immediately after searchRecord() returns can catch
     * the table mid-transition — findElement(Edit) on that stale row then
     * throws, gets swallowed by the catch block below, and the method just
     * silently reports "no row found" (or a previous version of this method
     * looked like it "did nothing"). isRecordPresent() already polls for
     * exactly this reason; this brings updateRecord() in line with it.
     */
    public void updateRecord(String searchText, String newDepartment) {
        WebElement editBtn;
        try {
            editBtn = wait.until(d -> {
                for (WebElement row : d.findElements(tableRows)) {
                    try {
                        if (!row.getText().trim().contains(searchText)) continue;
                        WebElement btn = row.findElement(By.cssSelector("span[title='Edit']"));
                        if (btn.isDisplayed()) return btn;
                    } catch (Exception e) {
                        // row stale mid-render — keep polling rather than giving up
                    }
                }
                return null;
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new RuntimeException("No row found containing: " + searchText, e);
        }

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", editBtn);
        js.executeScript("arguments[0].click();", editBtn);

        // Wait for modal to open
        wait.until(ExpectedConditions.visibilityOfElementLocated(departmentInput));

        // Clear and update department field
        WebElement deptField = driver.findElement(departmentInput);
        deptField.clear();
        HumanActions.typeHumanLike(deptField, newDepartment);

        clickSubmit();
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
     * <p>
     * Polls for the row + Delete button for the same reason updateRecord()
     * does — see the comment there.
     */
    public void deleteRecord(String searchText) {
        WebElement deleteBtn;
        try {
            deleteBtn = wait.until(d -> {
                for (WebElement row : d.findElements(tableRows)) {
                    try {
                        if (!row.getText().trim().contains(searchText)) continue;
                        WebElement btn = row.findElement(By.cssSelector("span[title='Delete']"));
                        if (btn.isDisplayed()) return btn;
                    } catch (Exception e) {
                        // row stale mid-render — keep polling rather than giving up
                    }
                }
                return null;
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new RuntimeException("No row found containing: " + searchText, e);
        }

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteBtn);
        js.executeScript("arguments[0].click();", deleteBtn);
        HumanActions.pause();
    }

    public void deleteFirstRecord() {
        WebElement deleteBtn = wait.until(
                ExpectedConditions.elementToBeClickable(deleteButtons));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteBtn);
        js.executeScript("arguments[0].click();", deleteBtn);
        HumanActions.pause();
    }
}
