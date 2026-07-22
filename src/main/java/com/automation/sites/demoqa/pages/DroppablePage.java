package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Point;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DroppablePage extends BasePage {

    // ── Navigation ──────────────────────────────────────────────
    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By droppableMenu    = By.xpath("//span[text()='Droppable']");

    // ── Tabs ────────────────────────────────────────────────────
    private final By acceptTab          = By.id("droppableExample-tab-accept");
    private final By preventPropTab     = By.id("droppableExample-tab-preventPropogation");
    private final By revertDraggableTab = By.id("droppableExample-tab-revertable");

    // ── Simple tab ───────────────────────────────────────────────
    private final By simpleDrag     = By.cssSelector("#droppableExample-tabpane-simple #draggable");
    private final By simpleDrop     = By.cssSelector("#droppableExample-tabpane-simple #droppable");
    private final By simpleDropText = By.cssSelector("#droppableExample-tabpane-simple #droppable p");

    // ── Accept tab ───────────────────────────────────────────────
    private final By acceptableDrag    = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[@id='acceptable']");
    private final By notAcceptableDrag = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drag-box') and not(@id='acceptable')]");
    private final By acceptDrop        = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]");
    private final By acceptDropText    = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]/p");

    // ── Prevent Propagation tab ─────────────────────────────────
    private final By preventDrag        = By.cssSelector("#droppableExample-tabpane-preventPropogation #dragBox");
    private final By innerNotGreedy     = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox");
    private final By innerGreedy        = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner");
    private final By outerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyDropBox > p");
    private final By innerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox > p");
    private final By outerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBox > p");
    private final By innerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner > p");

    // ── Revert Draggable tab ─────────────────────────────────────
    private final By willRevertDrag = By.cssSelector("#droppableExample-tabpane-revertable #revertable");
    private final By notRevertDrag  = By.cssSelector("#droppableExample-tabpane-revertable #notRevertable");
    private final By revertDrop     = By.cssSelector("#droppableExample-tabpane-revertable #droppable");
    private final By revertDropText = By.cssSelector("#droppableExample-tabpane-revertable #droppable p");

    public DroppablePage(WebDriver driver) {
        super(driver); // BasePage sets driver, wait (from config), js
    }

    /**
     * Drags {@code source} onto {@code target} using real native mouse
     * input ({@code Actions}), after disabling text selection on the page.
     * <p>
     * The underlying problem was never "incremental vs. direct movement" —
     * it was the browser's default text-selection drag hijacking a real
     * mousedown+move (visible as blue-highlighted text instead of an
     * actual drag). Switching to JS-dispatched synthetic events avoided
     * the selection hijack, but those events carry no real position state,
     * so the box never visually moved and "Dropped!" only registered when
     * a listener happened to fire regardless — which is exactly why
     * Accept/Greedy passed or failed inconsistently between runs.
     * <p>
     * The correct fix is to keep using real native input (so the page's
     * own drag logic runs normally and the box actually moves) but
     * suppress text selection first, so the browser can no longer
     * intercept the mousedown+move as a selection instead of a drag.
     */
    private void smoothDragToElement(WebElement source, WebElement target) {
        js.executeScript(
                "document.body.style.userSelect = 'none';" +
                        "document.body.style.webkitUserSelect = 'none';" +
                        "document.onselectstart = function() { return false; };" +
                        "window.getSelection().removeAllRanges();"
        );

        int steps = 15;
        Point sourceLoc = source.getLocation();
        Point targetLoc = target.getLocation();
        int totalX = targetLoc.getX() - sourceLoc.getX();
        int totalY = targetLoc.getY() - sourceLoc.getY();
        int stepX = totalX / steps;
        int stepY = totalY / steps;

        new Actions(driver).moveToElement(source).clickAndHold().perform();
        HumanActions.pause();

        for (int i = 0; i < steps; i++) {
            new Actions(driver).moveByOffset(stepX, stepY).perform();
            HumanActions.pause();
        }

        new Actions(driver).moveToElement(target).perform();
        HumanActions.pause();
        new Actions(driver).release().perform();
        HumanActions.pause();

        js.executeScript(
                "window.getSelection().removeAllRanges();" +
                        "document.onselectstart = null;"
        );
    }

    /**
     * Polls (rather than blindly sleeping) until the element found by
     * {@code locator} reaches {@code target}'s location or the timeout elapses.
     * <p>
     * jQuery UI's {@code revert: true} option snaps a draggable back to its
     * start position via an animated transition after drag-stop. A fixed
     * pause can race that animation and read the position mid-flight; this
     * waits for the actual end-state instead of guessing a sleep duration.
     * Silently returns on timeout so the caller's own assertion reports
     * whatever the final (possibly still-wrong) position turns out to be.
     */
    private void waitForLocationToStabilizeNear(By locator, Point target, Duration timeout) {
        try {
            new WebDriverWait(driver, timeout, Duration.ofMillis(100))
                    .until(d -> {
                        Point current = d.findElement(locator).getLocation();
                        return current.getX() == target.getX() && current.getY() == target.getY();
                    });
        } catch (TimeoutException ignored) {
            // Let the caller's own assertion report the mismatch.
        }
    }

    // ── Navigation ───────────────────────────────────────────────

    public void navigateToDroppable() {
        navigateTo("/droppable");
        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
        HumanActions.pause();
    }

    // ── Simple tab ───────────────────────────────────────────────

    public void dragToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            drag = driver.findElement(simpleDrag);
            drop = driver.findElement(simpleDrop);
            smoothDragToElement(drag, drop);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)))
                        .until(ExpectedConditions.textToBe(simpleDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        if (!success) {
            smoothDragToElement(driver.findElement(simpleDrag), driver.findElement(simpleDrop));
            HumanActions.pause();
        }
    }

    public String getSimpleDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDropText))
                .getText().trim();
    }

    // ── Accept tab ───────────────────────────────────────────────

    public void clickAcceptTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(acceptTab));
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-accept"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        HumanActions.pause();
    }

    public void dragAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();
        drag = driver.findElement(acceptableDrag);
        drop = driver.findElement(acceptDrop);
        smoothDragToElement(drag, drop);
        HumanActions.pause();
    }

    public void dragNotAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notAcceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();
        drag = driver.findElement(notAcceptableDrag);
        drop = driver.findElement(acceptDrop);
        smoothDragToElement(drag, drop);
        HumanActions.pause();
    }

    public void dragAcceptableAwayFromDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        new Actions(driver)
                .clickAndHold(drag)
                .moveByOffset(300, 0)
                .release()
                .build()
                .perform();
        HumanActions.pause();
    }

    public String getAcceptDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDropText))
                .getText().trim();
    }

    // ── Prevent Propagation tab ─────────────────────────────────

    public void clickPreventPropTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(preventPropTab));
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-preventPropogation"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        HumanActions.pause();
    }

    public void dragToInnerNotGreedy() {
        WebElement drag  = wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        WebElement inner = wait.until(ExpectedConditions.visibilityOfElementLocated(innerNotGreedy));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", inner);
        HumanActions.pause();

        drag  = driver.findElement(preventDrag);
        inner = driver.findElement(innerNotGreedy);
        smoothDragToElement(drag, inner);

        wait.until(ExpectedConditions.textToBe(innerNotGreedyText, "Dropped!"));
        wait.until(ExpectedConditions.textToBe(outerNotGreedyText, "Dropped!"));
    }

    public void dragToInnerGreedy() {
        WebElement drag  = wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        WebElement inner = wait.until(ExpectedConditions.visibilityOfElementLocated(innerGreedy));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", inner);
        HumanActions.pause();

        drag  = driver.findElement(preventDrag);
        inner = driver.findElement(innerGreedy);
        smoothDragToElement(drag, inner);

        wait.until(ExpectedConditions.textToBe(innerGreedyText, "Dropped!"));
    }

    public String getOuterNotGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(outerNotGreedyText)).getText().trim();
    }

    public String getInnerNotGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(innerNotGreedyText)).getText().trim();
    }

    public String getOuterGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(outerGreedyText)).getText().trim();
    }

    public String getInnerGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(innerGreedyText)).getText().trim();
    }

    // ── Revert Draggable tab ─────────────────────────────────────

    public void clickRevertDraggableTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(revertDraggableTab));
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-revertable"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
        HumanActions.pause();
    }

    public void dragWillRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();

        Point originalLocation = driver.findElement(willRevertDrag).getLocation();

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            drag = driver.findElement(willRevertDrag);
            drop = driver.findElement(revertDrop);
            smoothDragToElement(drag, drop);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)))
                        .until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }

        // The drop succeeding just means jQuery UI registered the drop event —
        // the revert:true animation back to originalLocation runs afterward and
        // takes a moment, so poll for it instead of guessing a sleep duration.
        waitForLocationToStabilizeNear(willRevertDrag, originalLocation, Duration.ofSeconds(5));
        HumanActions.pause();
    }

    public void dragNotRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            drag = driver.findElement(notRevertDrag);
            drop = driver.findElement(revertDrop);
            smoothDragToElement(drag, drop);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)))
                        .until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        HumanActions.pause();
    }

    public String getRevertDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(revertDropText)).getText().trim();
    }

    public Point getWillRevertBoxLocation() {
        return driver.findElement(willRevertDrag).getLocation();
    }

    public Point getNotRevertBoxLocation() {
        return driver.findElement(notRevertDrag).getLocation();
    }
}