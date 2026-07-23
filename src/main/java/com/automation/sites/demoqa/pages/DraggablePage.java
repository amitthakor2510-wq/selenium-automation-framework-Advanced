package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

public class DraggablePage extends BasePage {

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

    private final By cursorStyleTab   = By.id("draggableExample-tab-cursorStyle");
    private final By cursorCenterBox  = By.id("cursorCenter");
    private final By cursorTopLeftBox = By.id("cursorTopLeft");
    private final By cursorBottomBox  = By.id("cursorBottom");

    public DraggablePage(WebDriver driver) {
        super(driver);
    }

    public void navigateToDraggable() {
        navigateTo("/dragabble");
        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDragBox));
        HumanActions.pause();
    }

    private void smoothDrag(WebElement element, int totalX, int totalY) {
        int steps = 30;
        int stepX = totalX / steps;
        int stepY = totalY / steps;

        new Actions(driver).clickAndHold(element).perform();
        HumanActions.pause();

        for (int i = 0; i < steps; i++) {
            new Actions(driver).moveByOffset(stepX, stepY).perform();
            HumanActions.pause();
        }

        new Actions(driver).release().perform();
        HumanActions.pause();
    }

    /**
     * Scrolls to the located element, drags it, and VERIFIES the element's
     * location actually changed afterward.
     * <p>
     * DemoQA's draggable boxes occasionally do not respond to the very first
     * clickAndHold/move sequence issued right after a fresh page load or tab
     * switch (the jQuery UI draggable() binding appears to miss the initial
     * mousedown) even though the exact same drag mechanics work correctly on
     * every subsequent attempt. Rather than papering over this with extra
     * blind pauses, we re-locate the element and retry the drag once if its
     * position did not change.
     */
    private void dragWithRetry(By locator, int totalX, int totalY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();

        Point before = box.getLocation();
        smoothDrag(box, totalX, totalY);
        Point after = driver.findElement(locator).getLocation();

        boolean expectedMovement = (totalX != 0 || totalY != 0);
        if (expectedMovement && after.equals(before)) {
            WebElement retryBox = driver.findElement(locator);
            smoothDrag(retryBox, totalX, totalY);
        }
    }

    // ── Simple tab ──────────────────────────────────────────────────────────────

    public Point getDragBoxLocation() {
        return driver.findElement(simpleDragBox).getLocation();
    }

    public void dragSimpleBoxBy(int offsetX, int offsetY) {
        dragWithRetry(simpleDragBox, offsetX, offsetY);
    }

    // ── Axis restriction tab ────────────────────────────────────────────────────

    public void clickAxisTab() {
        HumanActions.click(driver, axisTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(onlyXBox));
        HumanActions.pause();
    }

    public void dragXOnlyBox(int offsetX) {
        dragWithRetry(onlyXBox, offsetX, 50);
    }

    public void dragYOnlyBox(int offsetY) {
        dragWithRetry(onlyYBox, 50, offsetY);
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

    public void dragContainedBoxBy(int offsetX, int offsetY) {
        dragWithRetry(containedBox, offsetX, offsetY);
    }

    public int getContainmentWrapperRightEdge() {
        WebElement wrapper = driver.findElement(containmentWrap);
        return wrapper.getLocation().getX() + wrapper.getSize().getWidth();
    }

    public int getContainmentWrapperBottomEdge() {
        WebElement wrapper = driver.findElement(containmentWrap);
        return wrapper.getLocation().getY() + wrapper.getSize().getHeight();
    }

    public Point getContainedBoxLocation() { return driver.findElement(containedBox).getLocation(); }

    // "I'm contained within my parent" is a SEPARATE draggable on this same tab —
    // it is a sibling of #containmentWrapper, not a child of it, and jQuery UI's
    // ui-draggable classes are applied to its <span> handle rather than a wrapping
    // div. It is constrained to its own immediate parent element, not to
    // #containmentWrapper. No selector previously existed for it.
    private final By containedWithinParentBox =
            By.xpath("//span[normalize-space()=\"I'm contained within my parent\"]");

    public void dragContainedWithinParentBoxBy(int offsetX, int offsetY) {
        dragWithRetry(containedWithinParentBox, offsetX, offsetY);
    }

    public Point getContainedWithinParentLocation() {
        return driver.findElement(containedWithinParentBox).getLocation();
    }

    public int getContainedWithinParentBoundaryRightEdge() {
        WebElement box = driver.findElement(containedWithinParentBox);
        WebElement parent = (WebElement) js.executeScript("return arguments[0].parentElement;", box);
        return parent.getLocation().getX() + parent.getSize().getWidth();
    }

    public int getContainedWithinParentBoundaryBottomEdge() {
        WebElement box = driver.findElement(containedWithinParentBox);
        WebElement parent = (WebElement) js.executeScript("return arguments[0].parentElement;", box);
        return parent.getLocation().getY() + parent.getSize().getHeight();
    }

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

    public String getCursorStyle(By locator) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        new Actions(driver).moveToElement(box).pause(Duration.ofMillis(500)).perform();
        return box.getCssValue("cursor");
    }

    public By getCursorCenterLocator()  { return cursorCenterBox; }
    public By getCursorTopLeftLocator() { return cursorTopLeftBox; }
    public By getCursorBottomLocator()  { return cursorBottomBox; }

    public Point getCursorBoxLocation(By locator) {
        return driver.findElement(locator).getLocation();
    }

    public void dragCursorBox(By locator, int offsetX, int offsetY) {
        dragWithRetry(locator, offsetX, offsetY);
    }
}
