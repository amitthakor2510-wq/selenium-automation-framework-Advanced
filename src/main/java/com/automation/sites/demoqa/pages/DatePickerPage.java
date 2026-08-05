package com.automation.sites.demoqa.pages;

import java.util.logging.Logger;

import com.automation.core.base.BasePage;
import com.automation.core.utils.ElementUtils;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.SmartLocator;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;

public class DatePickerPage extends BasePage {

    private static final Logger logger = Logger.getLogger(DatePickerPage.class.getName());

    private final By dateInput   = By.id("datePickerMonthYearInput");

    // Resolved through SmartLocator instead of driver.findElement() directly:
    // react-datepicker's own class names are the only locator confirmed against
    // the live site so far. If demoqa's date-picker library is ever swapped out
    // the same way the Check Box widget was (react-checkbox-tree -> rc-tree),
    // these fallbacks give the framework a chance to recover instead of failing
    // outright on the next CI run. Both fallbacks target the underlying native
    // <select> via its accessible name, which tends to survive a library swap
    // even when the wrapping CSS classes don't.
    private final By monthSelect         = By.className("react-datepicker__month-select");
    private final By monthSelectFallback = By.cssSelector("select[aria-label='Month']");
    private final By yearSelect          = By.className("react-datepicker__year-select");
    private final By yearSelectFallback  = By.cssSelector("select[aria-label='Year']");

    private final By dateTimeInput = By.id("dateAndTimePickerInput");

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

        WebElement monthDropdown = SmartLocator.find(driver, wait,
            "DatePicker month <select>", monthSelect, monthSelectFallback);
        HumanActions.pause();

        new Select(monthDropdown).selectByVisibleText(month);
        HumanActions.pause();

        WebElement yearDropdown = SmartLocator.find(driver, wait,
            "DatePicker year <select>", yearSelect, yearSelectFallback);
        new Select(yearDropdown).selectByVisibleText(year);
        HumanActions.pause();

        By dayLocator = By.xpath(
            "//div[contains(@class,'react-datepicker__day')" +
                " and not(contains(@class,'outside-month'))" +
                " and text()=" + ElementUtils.xpathLiteral(day) + "]"
        );
        wait.until(ExpectedConditions.elementToBeClickable(dayLocator));
        HumanActions.click(driver, dayLocator);
    }

    public String getSelectedDate() {
        return driver.findElement(dateInput).getAttribute("value");
    }

    /**
     * Sets the date-time by directly typing the full date-time string into the input.
     * The input field accepts the format: "mmm dd, yyyy hh:mm AM/PM"
     * Example: "May 15, 1999 10:30 PM"
     *
     * This bypasses the flaky date-time picker UI and directly sets the value
     * that the application expects, which is the most reliable approach for
     * automated testing of React form components.
     */
    public void selectDateTime(String month, String year, String day, String time) {
        logger.info("[DatePickerPage] Selecting date-time: " + month + " " + day + ", " + year + " @ " + time);

        // Build the date-time string in format the input expects
        String dateTimeStr = month + " " + day + ", " + year + " " + time;
        logger.info("[DatePickerPage] Setting input to: \"" + dateTimeStr + "\"");

        // BUG FIX: WebElement.clear() does not reliably clear this field.
        // react-datepicker keeps the input's displayed text in its own React
        // state rather than trusting the DOM's value attribute, and clear()
        // (a raw DOM-level clear) never fires the key events React listens
        // for, so the component's state — still holding today's date, the
        // picker's default — survives untouched. The subsequent sendKeys()
        // then appends the new text after that unchanged state on the next
        // re-render, producing a run-together value like
        // "August 5, 2026 11:41 AMMay 15, 1999 10:30 PM" instead of the
        // intended "May 15, 1999 10:30 PM" — silently wrong, not flaky,
        // since every attempt fails the exact same way. Confirmed in a real
        // CI run's logs. Fixed with the same Ctrl+A/Delete + verify-and-retry
        // approach RegistrationPage.fillField() already uses for the same
        // class of React-controlled-input problem.
        final int maxAttempts = 3;
        String result = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebElement input = wait.until(ExpectedConditions.elementToBeClickable(dateTimeInput));

            input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            input.sendKeys(Keys.DELETE);
            HumanActions.pause();
            input.sendKeys(dateTimeStr);
            HumanActions.pause();

            // Trigger React's change handlers
            js.executeScript(
                "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));" +
                    "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                input
            );
            HumanActions.pause();

            result = driver.findElement(dateTimeInput).getAttribute("value");
            logger.info("[DatePickerPage] After selection (attempt " + attempt + "/" + maxAttempts
                + "), input value: \"" + result + "\"");

            if (dateTimeStr.equals(result)) {
                return;
            }
            logger.info("[DatePickerPage] Value didn't match expected \"" + dateTimeStr + "\" — retrying");
        }

        throw new IllegalStateException(
            "Date-time input still didn't equal \"" + dateTimeStr + "\" after " + maxAttempts
                + " attempts — got \"" + result + "\" instead. Page may be unstable.");
    }

    public String getSelectedDateTime() {
        String value = driver.findElement(dateTimeInput).getAttribute("value");
        logger.info("[DatePickerPage] getSelectedDateTime() returning: \"" + value + "\"");
        return value;
    }
}
