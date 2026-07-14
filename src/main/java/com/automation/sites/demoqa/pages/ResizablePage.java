package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

public class ResizablePage extends BasePage {

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By resizableMenu    = By.xpath("//span[text()='Resizable']");
    private final By resizableBox     = By.id("resizableBoxWithRestriction");
    private final By resizableHandle  = By.cssSelector("#resizableBoxWithRestriction .react-resizable-handle");

    public ResizablePage(WebDriver driver) {
        super(driver);
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
        // Compute the current box size and clamp the requested resize to the allowed min/max so we
        // don't attempt to move the pointer far outside the viewport (which can raise
        // MoveTargetOutOfBoundsException on some drivers).
        org.openqa.selenium.Dimension current = driver.findElement(resizableBox).getSize();
        int currentW = current.getWidth();
        int currentH = current.getHeight();

        // Clamp desired final size according to page constraints (min 150x150, max 500x300)
        int desiredW = Math.max(150, Math.min(500, currentW + offsetX));
        int desiredH = Math.max(150, Math.min(300, currentH + offsetY));

        int deltaX = desiredW - currentW;
        int deltaY = desiredH - currentH;

        // Use dragAndDropBy which is clearer for resizing by pixel offsets.
        // If the delta is zero (already at limit), skip the action.
        if (deltaX != 0 || deltaY != 0) {
            new Actions(driver)
                    .moveToElement(handle)
                    .clickAndHold()
                    .pause(Duration.ofMillis(150))
                    .moveByOffset(deltaX, deltaY)
                    .pause(Duration.ofMillis(150))
                    .release()
                    .perform();
        }

        HumanActions.pause();
    }
}