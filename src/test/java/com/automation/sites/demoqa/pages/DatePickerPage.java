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

public class DatePickerPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard      = By.xpath("//h5[text()='Widgets']");
    private final By datePickerMenu   = By.xpath("//span[text()='Date Picker']");

    // ── Date picker ────────────────────────────────────────────────────────────
    private final By dateInput        = By.id("datePickerMonthYearInput");
    private final By monthSelect      = By.className("react-datepicker__month-select");
    private final By yearSelect       = By.className("react-datepicker__year-select");

    // ── Date and time picker ───────────────────────────────────────────────────
    private final By dateTimeInput    = By.id("dateAndTimePickerInput");
    private final By timeList         = By.className("react-datepicker__time-list");
    private final By timeListItem     = By.cssSelector(
            ".react-datepicker__time-list-item"
    );

    public DatePickerPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToDatePicker() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, datePickerMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(dateInput));
    }

    // ── Select date ────────────────────────────────────────────────────────────

    /**
     * Opens the date picker, selects month and year,
     * then clicks the day.
     */
    public void selectDate(String month, String year, String day) {
        HumanActions.click(driver, dateInput);
        wait.until(ExpectedConditions.visibilityOfElementLocated(monthSelect));
        HumanActions.pause();

        new Select(driver.findElement(monthSelect)).selectByVisibleText(month);
        HumanActions.pause();

        new Select(driver.findElement(yearSelect)).selectByVisibleText(year);
        HumanActions.pause();

        By dayLocator = By.xpath(
                "//div[contains(@class,'react-datepicker__day')" +
                        " and not(contains(@class,'outside-month'))" +
                        " and not(contains(@class,'keyboard-selected'))" +
                        " and text()='" + day + "']"
        );
        wait.until(ExpectedConditions.elementToBeClickable(dayLocator));
        HumanActions.click(driver, dayLocator);
    }

    public String getSelectedDate() {
        return driver.findElement(dateInput).getAttribute("value");
    }

    // ── Date and time picker ───────────────────────────────────────────────────

    /**
     * Clears the date-time input, types a new date directly,
     * then presses Enter to confirm.
     * Simpler than using the full date-time picker UI.
     */
    public void selectDateTime(String dateTimeValue) {
        WebElement input = wait.until(
                ExpectedConditions.elementToBeClickable(dateTimeInput)
        );
        HumanActions.pause();

        // Clear existing value with Ctrl+A then type new value
        input.sendKeys(Keys.CONTROL + "a");
        input.sendKeys(dateTimeValue);
        input.sendKeys(Keys.ENTER);
        HumanActions.pause();
    }

    public String getSelectedDateTime() {
        return driver.findElement(dateTimeInput).getAttribute("value");
    }
}