package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class ResizablePage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By resizableMenu    = By.xpath("//span[text()='Resizable']");
    private final By resizableBox     = By.id("resizableBoxWithRestriction");
    private final By resizableHandle  = By.cssSelector("#resizableBoxWithRestriction .react-resizable-handle");

    public ResizablePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToResizable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", card);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", card);

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(resizableMenu));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(resizableBox));
        WebElement box = driver.findElement(resizableBox);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
    }

    /**
     * Returns width and height of the box as a Dimension object.
     *
     * NEW CONCEPT — element.getSize() returns Dimension(width, height):
     * Use this to assert size before and after a resize operation.
     */
    public Dimension getBoxSize() {
        return driver.findElement(resizableBox).getSize();
    }

    /**
     * Drags the resize handle by offsetX pixels right and offsetY pixels down.
     *
     * NEW CONCEPT — moveByOffset(x, y):
     * Moves the mouse BY a pixel amount from its current position.
     * Unlike moveToElement() which moves TO an element,
     * moveByOffset is used when you need pixel-level drag control.
     * Positive x = right, positive y = down, negative = opposite direction.
     * Box is clamped: min 150x150, max 500x300.
     */
    public void resizeBy(int offsetX, int offsetY) {
        WebElement handle = wait.until(ExpectedConditions.visibilityOfElementLocated(resizableHandle));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", handle);
        HumanActions.pause();

        new Actions(driver)
                .clickAndHold(handle)
                .pause(Duration.ofMillis(300))
                .moveByOffset(offsetX, offsetY)
                .pause(Duration.ofMillis(300))
                .release()
                .perform();

        HumanActions.pause();
    }
}