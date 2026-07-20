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

    public void dragContainedBoxBy(int offsetX, int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(containedBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, offsetY);
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

    public void dragCursorBox(By locator, int offsetX, int offsetY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();
        smoothDrag(box, offsetX, offsetY);
    }
}