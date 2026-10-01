package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResizablePage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(ResizablePage.class);

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By resizableMenu    = By.xpath("//span[text()='Resizable']");
    private final By resizableBox     = By.id("resizableBoxWithRestriction");
    // BUG FIX (confirmed against a live Jenkins run — ResizableTest#verifyResizeIncrease
    // failed: width correctly grew 200->300 but height went 200->174, i.e. it SHRANK,
    // for a drag offset of (+100, +50)): react-resizable renders one handle <span> per
    // active resize direction, and the un-suffixed ".react-resizable-handle" selector
    // matches ALL of them. driver.findElement() silently returns whichever one is
    // FIRST in DOM order, which — for this box — is the north-east (top-right) corner
    // handle, not the south-east (bottom-right) one this page object's javadoc and
    // resizeBy() both assume. Dragging the NE handle down-and-right moves the box's
    // right edge out (width +100, matching what was observed) while ALSO moving its
    // TOP edge down (height -50-ish, matching the observed shrink) — exactly the
    // symptom in the log. Scoping the selector to the "-se" (south-east) class name
    // pins it to the actual bottom-right handle the offsets in this file are written
    // for.
    private final By resizableHandle  =
        By.cssSelector("#resizableBoxWithRestriction .react-resizable-handle.react-resizable-handle-se");

    public ResizablePage(WebDriver driver) {
        super(driver);
    }

    public void navigateToResizable() {
        navigateTo("/resizable");
        wait.until(ExpectedConditions.visibilityOfElementLocated(resizableBox));
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
     * Drags the resize handle in small steps rather than one big jump.
     * <p>
     * BUG FIX (confirmed against a live Jenkins run — ResizableTest#verifyResizeIncrease
     * failed AGAIN even after the handle selector was correctly scoped to the
     * "-se" (bottom-right) handle: Before 200x200, After 300x174 — width grew
     * correctly but height still shrank, the exact same symptom the old
     * unscoped-selector bug produced, which ruled out a wrong-handle theory.
     * The actual cause is the drag mechanics, not the locator: the previous
     * implementation did ONE moveToElement().clickAndHold().moveByOffset(deltaX,
     * deltaY).release() — a single large jump. react-resizable's DraggableCore
     * computes each resize increment from a STREAM of mousemove events relative
     * to the last one it saw, the same class of library as jQuery UI's
     * draggable() (see DraggablePage.smoothDrag(), which this mirrors) — a
     * single huge synthetic jump is exactly the pattern already found to
     * produce unreliable/incorrect deltas for that kind of listener, rather
     * than a clean one-shot "move to this final position". Breaking the same
     * offset into many small incremental moveByOffset() steps (with a short
     * pause between each, matching smoothDrag()) gives the library a normal
     * stream of small deltas to accumulate instead of one it may mis-track.
     */
    private static final int RESIZE_STEPS = 30;

    private void smoothResizeDrag(WebElement handle, int totalX, int totalY) {
        new Actions(driver).clickAndHold(handle).perform();
        HumanActions.microPause();

        int baseStepX = totalX / RESIZE_STEPS;
        int baseStepY = totalY / RESIZE_STEPS;
        int remX = totalX - baseStepX * RESIZE_STEPS;
        int remY = totalY - baseStepY * RESIZE_STEPS;

        for (int i = 0; i < RESIZE_STEPS; i++) {
            int stepX = baseStepX + (Math.abs(i) < Math.abs(remX) ? Integer.signum(remX) : 0);
            int stepY = baseStepY + (Math.abs(i) < Math.abs(remY) ? Integer.signum(remY) : 0);
            if (stepX != 0 || stepY != 0) {
                new Actions(driver).moveByOffset(stepX, stepY).perform();
            }
            HumanActions.microPause();
        }

        new Actions(driver).release().perform();
        HumanActions.microPause();
    }

    /**
     * Drags the resize handle by offsetX pixels right and offsetY pixels down.
     * Box is clamped: min 150x150, max 500x300. Retries the drag (re-locating
     * the handle fresh each time) if the resulting size didn't actually move
     * in the expected direction — same defensive pattern as
     * DraggablePage#dragWithRetry(), for the same class of first-attempt
     * flakiness on this app's drag-driven widgets.
     */
    private static final int MAX_RESIZE_ATTEMPTS = 3;

    public void resizeBy(int offsetX, int offsetY) {
        // Compute the current box size and clamp the requested resize to the allowed min/max so we
        // don't attempt to move the pointer far outside the viewport (which can raise
        // MoveTargetOutOfBoundsException on some drivers).
        Dimension current = driver.findElement(resizableBox).getSize();
        int currentW = current.getWidth();
        int currentH = current.getHeight();

        // Clamp desired final size according to page constraints (min 150x150, max 500x300)
        int desiredW = Math.max(150, Math.min(500, currentW + offsetX));
        int desiredH = Math.max(150, Math.min(300, currentH + offsetY));

        int deltaX = desiredW - currentW;
        int deltaY = desiredH - currentH;

        if (deltaX == 0 && deltaY == 0) {
            HumanActions.pause();
            return;
        }

        for (int attempt = 1; attempt <= MAX_RESIZE_ATTEMPTS; attempt++) {
            WebElement handle = wait.until(ExpectedConditions.visibilityOfElementLocated(resizableHandle));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", handle);
            HumanActions.pause();

            smoothResizeDrag(handle, deltaX, deltaY);

            Dimension after = driver.findElement(resizableBox).getSize();
            boolean widthOk  = deltaX == 0 || (deltaX > 0 ? after.getWidth()  > currentW : after.getWidth()  < currentW);
            boolean heightOk = deltaY == 0 || (deltaY > 0 ? after.getHeight() > currentH : after.getHeight() < currentH);

            if (widthOk && heightOk) {
                HumanActions.pause();
                return;
            }
            logger.warn("[ResizablePage] Resize attempt " + attempt + "/" + MAX_RESIZE_ATTEMPTS
                + " produced unexpected size " + after.getWidth() + "x" + after.getHeight()
                + " (before " + currentW + "x" + currentH + ", requested delta "
                + deltaX + "," + deltaY + ") — retrying");
        }

        HumanActions.pause();
    }
}
