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
    private final By widgetsCard  = By.xpath("//h5[text()='Widgets']");
    private final By sliderMenu   = By.xpath("//span[text()='Slider']");

    // ── Slider ─────────────────────────────────────────────────────────────────
    private final By sliderInput  = By.cssSelector("input[type='range']");
    private final By sliderValue  = By.id("sliderValue");

    public SliderPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToSlider() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, sliderMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(sliderInput));
    }

    /**
     * Sets the slider to a specific value using JavaScript.
     * More reliable than Actions drag because the slider
     * position depends on screen size when dragging.
     *
     * value → number between 0 and 100
     */
    public void setSliderValue(int value) {
        WebElement slider = wait.until(
                ExpectedConditions.visibilityOfElementLocated(sliderInput)
        );

        // Set value directly via JavaScript
        js.executeScript(
                "arguments[0].value = arguments[1];" +
                        "arguments[0].dispatchEvent(new Event('change'));" +
                        "arguments[0].dispatchEvent(new Event('input'));",
                slider, value
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