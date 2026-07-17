package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;

public class DatePickerPage extends BasePage {

    // ── Date picker ────────────────────────────────────────────────────────────
    private final By dateInput   = By.id("datePickerMonthYearInput");
    private final By monthSelect = By.className("react-datepicker__month-select");
    private final By yearSelect  = By.className("react-datepicker__year-select");

    // ── Date and time picker ───────────────────────────────────────────────────
    private final By dateTimeInput = By.id("dateAndTimePickerInput");
    private final By timeList      = By.className("react-datepicker__time-list");
    private final By timeListItem  = By.cssSelector(".react-datepicker__time-list-item");

    public DatePickerPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToDatePicker() {
        navigateTo("/date-picker");
        wait.until(ExpectedConditions.visibilityOfElementLocated(dateInput));
        HumanActions.pause();
    }

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

    public void selectDateTime(String dateTimeValue) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(dateTimeInput));
        HumanActions.pause();
        input.sendKeys(Keys.CONTROL + "a");
        input.sendKeys(dateTimeValue);
        input.sendKeys(Keys.ENTER);
        HumanActions.pause();
    }

    public String getSelectedDateTime() {
        return driver.findElement(dateTimeInput).getAttribute("value");
    }
}