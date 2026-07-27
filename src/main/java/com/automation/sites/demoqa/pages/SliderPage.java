package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class SliderPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard = By.xpath("//h5[text()='Widgets']");
    private final By sliderMenu  = By.xpath("//span[text()='Slider']");

    // ── Slider ─────────────────────────────────────────────────────────────────
    private final By sliderInput = By.cssSelector("input[type='range']");
    private final By sliderValue = By.id("sliderValue");

    public SliderPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToSlider() {
        navigateTo("/slider");
        wait.until(ExpectedConditions.visibilityOfElementLocated(sliderInput));
        HumanActions.pause();
    }

    public void setSliderValue(int value) {
        WebElement slider = driver.findElement(sliderInput);

        // Use React's native input setter — plain .value assignment is ignored
        // by React's synthetic event system and the display stays at default (25)
        js.executeScript(
            "var nativeInputValueSetter = Object.getOwnPropertyDescriptor(" +
                "    window.HTMLInputElement.prototype, 'value').set;" +
                "nativeInputValueSetter.call(arguments[0], arguments[1]);" +
                "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));",
            slider, String.valueOf(value)
        );
        HumanActions.pause();
    }

    public String getSliderValue() {
        return wait.until(
            ExpectedConditions.visibilityOfElementLocated(sliderValue)
        ).getAttribute("value");
    }

    public String getSliderInputValue() {
        return driver.findElement(sliderInput).getAttribute("value");
    }
}
