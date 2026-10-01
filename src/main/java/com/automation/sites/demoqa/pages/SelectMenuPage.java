package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.ElementUtils;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;

public class SelectMenuPage extends BasePage {

    // ── Select Value (react-select) ────────────────────────────────────────────
    // Target by the input ID we know is correct, then walk up to the control div
    private final By selectValueInput   = By.id("react-select-2-input");
    private final By selectValueDisplay = By.cssSelector(
        "#react-select-2-input ~ * .react-select__single-value, " +
            "div:has(> #react-select-2-input) .react-select__single-value"
    );

    // ── Old style select ───────────────────────────────────────────────────────
    private final By oldStyleSelect = By.id("oldSelectMenu");

    // ── Standard multi select ──────────────────────────────────────────────────
    private final By standardMulti = By.id("cars");

    public SelectMenuPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToSelectMenu() {
        navigateTo("/select-menu");
        wait.until(ExpectedConditions.presenceOfElementLocated(oldStyleSelect));
        HumanActions.pause();
    }

    // ── Select Value ───────────────────────────────────────────────────────────

    public void selectValue(String value) {
        WebElement input = wait.until(
            ExpectedConditions.presenceOfElementLocated(selectValueInput)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", input);
        HumanActions.pause();

        // Open the dropdown by clicking the control div.
        // react-select on DemoQA uses hashed CSS classes (e.g. css-yk16xz-control),
        // so .closest('.react-select__control') returns null.
        // The DOM structure is: input → div (value-container) → div (control)
        // so parentElement.parentElement is always the control, class-name-agnostic.
        js.executeScript(
            "arguments[0].parentElement.parentElement.click();", input
        );

        // Type into the now-open input
        input = wait.until(
            ExpectedConditions.elementToBeClickable(selectValueInput)
        );
        input.sendKeys(value);

        // Click the matching option
        By option = By.xpath(
            "//div[contains(@class,'option') and normalize-space(.)=" + ElementUtils.xpathLiteral(value) + "]"
        );
        WebElement opt = wait.until(ExpectedConditions.elementToBeClickable(option));
        HumanActions.pause();
        opt.click();
    }

    public String getSelectValue() {
        // react-select on DemoQA uses hashed CSS classes (css-1uccc91-singleValue etc.)
        // so class-based selectors are unreliable. Instead read the aria-label or
        // the dummy input value that react-select keeps in sync after selection.
        // The simplest approach: grab the input's current value attribute.
        WebElement input = wait.until(
            ExpectedConditions.presenceOfElementLocated(selectValueInput)
        );
        // react-select sets the parent container's aria-label to the selected value
        // and also puts it as a sibling div. Use the grandparent's text content
        // minus the input's own placeholder text via JS to get the selected label.
        String selected = (String) js.executeScript(
            "var c = arguments[0].parentElement.parentElement;" +
                "var sv = c.querySelector('[class*=\"singleValue\"]');" +
                "return sv ? sv.innerText : null;",
            input
        );
        return selected != null ? selected : "";
    }

    // ── Old style select ───────────────────────────────────────────────────────

    public void selectOldStyleOption(String visibleText) {
        WebElement select = wait.until(
            ExpectedConditions.visibilityOfElementLocated(oldStyleSelect)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", select);
        HumanActions.pause();
        new Select(select).selectByVisibleText(visibleText);
    }

    public String getOldStyleSelectedValue() {
        WebElement select = driver.findElement(oldStyleSelect);
        return new Select(select).getFirstSelectedOption().getText();
    }

    // ── Standard multi select ──────────────────────────────────────────────────

    public void selectCarOption(String visibleText) {
        WebElement select = wait.until(
            ExpectedConditions.visibilityOfElementLocated(standardMulti)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", select);
        HumanActions.pause();
        Select sel = new Select(select);
        sel.deselectAll();
        sel.selectByVisibleText(visibleText);
    }

    public String getSelectedCarOption() {
        return new Select(driver.findElement(standardMulti))
            .getFirstSelectedOption().getText();
    }
}
