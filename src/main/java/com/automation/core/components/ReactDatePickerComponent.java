package com.automation.core.components;

import com.automation.core.utils.ElementUtils;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.SmartLocator;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Reusable react-datepicker month/year/day picker — the same widget behind both
 * {@code DatePickerPage}'s standalone date picker and {@code PracticeFormPage}'s date-of-birth
 * field. Both pages open it by clicking a text input, then pick a month and year from two plain
 * HTML {@code <select>}s, then click the matching day cell — identical sequence, identical
 * locators, previously hand-rolled twice.
 *
 * <p>Consolidating found a real gap, not just duplicated text: {@code DatePickerPage} already
 * resolved the month/year {@code <select>}s through {@link SmartLocator} with a fallback
 * (targeting the underlying {@code <select>}'s accessible name, which tends to survive a
 * date-picker-library swap even when the wrapping CSS classes don't — see the comment that used
 * to sit above {@code DatePickerPage}'s own locators), while {@code PracticeFormPage}'s copy
 * used {@code driver.findElement(...)} directly with no fallback at all. Every caller of this
 * component now gets the more resilient version; {@code PracticeFormPage} previously did not.
 *
 * <p>Construct one instance per date-picker field a page has (most pages have exactly one),
 * passing whatever locator opens it (usually the text input showing the currently-selected
 * date) plus fallback locators for the month/year {@code <select>}s. See
 * {@code docs/architecture.md#-component-based-page-objects} for the fuller writeup, including
 * why the datepicker's plain-text-entry sibling field (e.g. {@code DatePickerPage}'s
 * date-and-time input) is deliberately NOT part of this component — that's a different widget
 * (direct text entry, no month/year/day picker UI at all), not another instance of this one.
 */
public class ReactDatePickerComponent {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final By openTrigger;
    private final By monthSelectLocator;
    private final By monthSelectFallback;
    private final By yearSelectLocator;
    private final By yearSelectFallback;

    /**
     * @param openTrigger         locator for whatever, when clicked, opens this date picker
     *                            (typically the text input showing the current value)
     * @param monthSelectLocator  primary locator for the month {@code <select>}
     * @param monthSelectFallback fallback locator for the month {@code <select>}, tried if the
     *                            primary one isn't found — see {@link SmartLocator}
     * @param yearSelectLocator   primary locator for the year {@code <select>}
     * @param yearSelectFallback  fallback locator for the year {@code <select>}
     */
    public ReactDatePickerComponent(WebDriver driver, WebDriverWait wait, By openTrigger,
                                    By monthSelectLocator, By monthSelectFallback,
                                    By yearSelectLocator, By yearSelectFallback) {
        this.driver = driver;
        this.wait = wait;
        this.openTrigger = openTrigger;
        this.monthSelectLocator = monthSelectLocator;
        this.monthSelectFallback = monthSelectFallback;
        this.yearSelectLocator = yearSelectLocator;
        this.yearSelectFallback = yearSelectFallback;
    }

    /** Opens the picker, selects the given month/year/day, same sequence every caller needs. */
    public void selectDate(String month, String year, String day) {
        HumanActions.click(driver, openTrigger);

        WebElement monthDropdown = SmartLocator.find(driver, wait,
            "date picker month <select>", monthSelectLocator, monthSelectFallback);
        HumanActions.pause();

        new Select(monthDropdown).selectByVisibleText(month);
        HumanActions.pause();

        WebElement yearDropdown = SmartLocator.find(driver, wait,
            "date picker year <select>", yearSelectLocator, yearSelectFallback);
        new Select(yearDropdown).selectByVisibleText(year);
        HumanActions.pause();

        By dayLocator = By.xpath(
            "//div[contains(@class,'react-datepicker__day')"
                + " and not(contains(@class,'outside-month'))"
                + " and text()=" + ElementUtils.xpathLiteral(day) + "]"
        );
        wait.until(ExpectedConditions.elementToBeClickable(dayLocator));
        HumanActions.click(driver, dayLocator);
    }
}
