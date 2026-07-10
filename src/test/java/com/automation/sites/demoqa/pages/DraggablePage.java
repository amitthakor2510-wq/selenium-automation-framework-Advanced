package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DraggablePage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By draggableMenu    = By.xpath("//span[text()='Dragabble']");

    private final By simpleTab     = By.id("draggableExample-tab-simple");
    private final By simpleDragBox = By.id("dragBox");

    private final By axisTab  = By.id("draggableExample-tab-axisRestriction");
    private final By onlyXBox = By.id("restrictedX");
    private final By onlyYBox = By.id("restrictedY");

    private final By containerTab    = By.id("draggableExample-tab-containerRestriction");
    private final By containedBox    = By.cssSelector("#containmentWrapper > div");
    private final By containmentWrap = By.id("containmentWrapper");

    // These elements live inside a sandboxed iframe in the cursorStyle tabpane
    // Must switch into the iframe before interacting with them
    // ── Cursor style tab ────────────────────────────────────────────────────────
    private final By cursorStyleTab   = By.id("draggableExample-tab-cursorStyle");
    private final By cursorCenterBox  = By.id("cursorCenter");
    private final By cursorTopLeftBox = By.id("cursorTopLeft");
    private final By cursorBottomBox  = By.id("cursorBottom");

    public DraggablePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToDraggable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", card);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", card);

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(draggableMenu));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDragBox));
        WebElement box = driver.findElement(simpleDragBox);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
    }

    /**
     * Smooth drag — each step is a separate perform() with a real Thread.sleep().
     */
    private void smoothDrag(WebElement element, int totalX, int totalY) {
        int steps = 30;
        int stepX = totalX / steps;
        int stepY = totalY / steps;

        new Actions(driver).clickAndHold(element).perform();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        for (int i = 0; i < steps; i++) {
            new Actions(driver).moveByOffset(stepX, stepY).perform();
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        new Actions(driver).release().perform();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Simple tab ──────────────────────────────────────────────────────────────

    public Point getDragBoxLocation() {
        return driver.findElement(simpleDragBox).getLocation();
    }

    public void dragSimpleBoxBy(int offsetX, int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDragBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, offsetY);
    }

    // ── Axis restriction tab ────────────────────────────────────────────────────

    public void clickAxisTab() {
        HumanActions.click(driver, axisTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(onlyXBox));
        HumanActions.pause();
    }

    public void dragXOnlyBox(int offsetX) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(onlyXBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, 50);
    }

    public void dragYOnlyBox(int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(onlyYBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, 50, offsetY);
    }

    public Point getXOnlyBoxLocation() { return driver.findElement(onlyXBox).getLocation(); }
    public Point getYOnlyBoxLocation() { return driver.findElement(onlyYBox).getLocation(); }

    // ── Container restriction tab ───────────────────────────────────────────────

    public void clickContainerTab() {
        HumanActions.click(driver, containerTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(containedBox));
        HumanActions.pause();
        WebElement box = driver.findElement(containedBox);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
    }

    /**
     * Drags contained box and verifies it stays within its parent wrapper.
     * smoothDrag handles the movement — jQuery UI stops it at the boundary.
     */
    public void dragContainedBoxBy(int offsetX, int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(containedBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, offsetY);
    }

    /**
     * Returns the right edge of the containment wrapper so the test can
     * assert the box stayed inside it.
     */
    public int getContainmentWrapperRightEdge() {
        WebElement wrapper = driver.findElement(containmentWrap);
        return wrapper.getLocation().getX() + wrapper.getSize().getWidth();
    }

    public int getContainmentWrapperBottomEdge() {
        WebElement wrapper = driver.findElement(containmentWrap);
        return wrapper.getLocation().getY() + wrapper.getSize().getHeight();
    }

    public Point getContainedBoxLocation() { return driver.findElement(containedBox).getLocation(); }

    // ── Cursor style tab ────────────────────────────────────────────────────────

    public void clickCursorStyleTab() {
        WebElement tab = wait.until(ExpectedConditions.presenceOfElementLocated(cursorStyleTab));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", tab);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", tab);

        wait.until(ExpectedConditions.visibilityOfElementLocated(cursorCenterBox));
        WebElement box = driver.findElement(cursorCenterBox);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
    }

    /**
     * Hovers over a cursor box and returns its computed CSS cursor value.
     * No iframe involved — elements are directly in the main document.
     *
     * All three boxes have cursor=move (jQuery UI draggable default).
     * What differs is the 'cursorAt' option — where the cursor attaches
     * to the element during drag (center, top-left, bottom-center).
     * We verify cursor=move is applied on all three.
     */
    public String getCursorStyle(By locator) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();

        new Actions(driver)
                .moveToElement(box)
                .pause(Duration.ofMillis(500))
                .perform();

        return box.getCssValue("cursor");
    }

    public By getCursorCenterLocator()  { return cursorCenterBox; }
    public By getCursorTopLeftLocator() { return cursorTopLeftBox; }
    public By getCursorBottomLocator()  { return cursorBottomBox; }

    private By getCursorStyleTab() {
        return cursorStyleTab;
    }

    public void dragCursorBox(By locator, int offsetX, int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, offsetY);
    }
}