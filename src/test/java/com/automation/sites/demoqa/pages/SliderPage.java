package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class SliderPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard = By.xpath("//h5[text()='Widgets']");
    private final By sliderMenu  = By.xpath("//span[text()='Slider']");

    // ── Slider ─────────────────────────────────────────────────────────────────
    private final By sliderInput = By.cssSelector("input[type='range']");
    private final By sliderValue = By.id("sliderValue");

    public SliderPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToSlider() {
        HumanActions.click(driver, widgetsCard);
        WebElement menuItem = wait.until(
                ExpectedConditions.presenceOfElementLocated(sliderMenu)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menuItem);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menuItem);

        // Wait for slider, then scroll it into view so the interaction is visible
        WebElement slider = wait.until(
                ExpectedConditions.visibilityOfElementLocated(sliderInput)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", slider);
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