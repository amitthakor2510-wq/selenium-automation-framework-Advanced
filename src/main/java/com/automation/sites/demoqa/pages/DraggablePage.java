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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DraggablePage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(DraggablePage.class);

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By draggableMenu    = By.xpath("//span[text()='Dragabble']");

    private final By simpleTab     = By.id("draggableExample-tab-simple");
    private final By simpleDragBox = By.id("dragBox");

    // BUG FIX (confirmed against a fresh local run — DraggableTest#verifyXAxisRestriction
    // failed with "X should change did not expect [1024] but found [1024]" and
    // #verifyYAxisRestriction failed with "X should NOT change ... expected [625] but
    // found [675]"): a prior hardening pass swapped these two ids on the theory that
    // demoqa names each element for the axis that's LOCKED rather than the axis it's
    // free to move on. This run's before/after coordinates disprove that theory —
    // dragging the element the code called "onlyXBox" (id="restrictedY") moved its Y
    // coordinate by exactly the requested Y offset and left X untouched, and dragging
    // "onlyYBox" (id="restrictedX") moved X by exactly the requested X offset and left
    // Y untouched. So the ids name the axis the box IS free to move on, the plain
    // reading: id="restrictedX" is the box restricted TO the X axis (X-only movable),
    // and id="restrictedY" is the box restricted TO the Y axis (Y-only movable).
    // Reverted the earlier swap so "onlyXBox" really is the X-only-movable element,
    // matching what DraggableTest's method names and assertions expect.
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

    /**
     * Returns an element's position relative to the DOCUMENT, immune to
     * whatever the page's scroll offset happens to be at read time.
     * <p>
     * BUG FIX: every location getter in this class used to call the plain
     * {@code WebElement.getLocation()}. Under the W3C WebDriver protocol
     * that call is backed by {@code getBoundingClientRect()}, which is
     * relative to the VIEWPORT, not the document — it shifts by however
     * much the page has scrolled. {@link #dragWithRetry} always scrolls
     * the target element into view (block:'center') before dragging it,
     * but callers typically capture a "before" location *before* invoking
     * the drag method and an "after" location *after* it returns — so the
     * scroll that happens in between silently contaminates the vertical
     * (and potentially horizontal) delta, on top of whatever the drag
     * itself actually did. This is exactly what caused
     * DraggableTest#verifyXAxisRestriction / #verifyYAxisRestriction to
     * fail: a real jQuery UI axis-lock (fixed X or fixed Y coordinate)
     * held on one axis, while the *other* axis's reading moved partly
     * because of the real drag and partly because of the intervening
     * scroll — neither the strict assertEquals (should NOT change) nor
     * the assertNotEquals (should change) can be trusted against a
     * viewport-relative number. Adding window.pageXOffset/pageYOffset
     * back in makes the coordinate document-relative and therefore
     * scroll-invariant, so before/after comparisons only reflect real
     * element movement.
     */
    @SuppressWarnings("unchecked")
    private Point getPageRelativeLocation(WebElement el) {
        Map<String, Object> rect = (Map<String, Object>) js.executeScript(
            "const r = arguments[0].getBoundingClientRect();"
                + "return {x: r.left + window.pageXOffset, y: r.top + window.pageYOffset};",
            el);
        return new Point(((Number) rect.get("x")).intValue(), ((Number) rect.get("y")).intValue());
    }

    private Point getPageRelativeLocation(By locator) {
        return getPageRelativeLocation(driver.findElement(locator));
    }

    private void smoothDrag(WebElement element, int totalX, int totalY) {
        int steps = 30;

        new Actions(driver).clickAndHold(element).perform();
        // Unconditional (not HumanActions.pause(), which is a full no-op
        // under -Dhuman.pause.enabled=false as every Jenkins/CI regression
        // run sets) — jQuery UI's draggable() needs a real gap after
        // mousedown before the first mousemove or it can miss the drag
        // start entirely. See HumanActions.microPause() for the full story.
        HumanActions.microPause();

        // BUG FIX: stepX/stepY used to be computed once via plain integer
        // division (totalX/steps), which truncates and silently drops the
        // remainder — e.g. a 50px total over 30 steps produced stepY=1,
        // moving only 30px total (30 * 1), 40% short of the requested
        // offset. Distributing the remainder across the first `remainder`
        // steps (one extra pixel each) instead means the sum of all
        // per-step moves always equals EXACTLY totalX/totalY, regardless
        // of how evenly `steps` divides them.
        int baseStepX = totalX / steps;
        int baseStepY = totalY / steps;
        int remX = totalX - baseStepX * steps;
        int remY = totalY - baseStepY * steps;

        for (int i = 0; i < steps; i++) {
            int stepX = baseStepX + (Math.abs(i) < Math.abs(remX) ? Integer.signum(remX) : 0);
            int stepY = baseStepY + (Math.abs(i) < Math.abs(remY) ? Integer.signum(remY) : 0);
            new Actions(driver).moveByOffset(stepX, stepY).perform();
            HumanActions.microPause();
        }

        new Actions(driver).release().perform();
        HumanActions.microPause();
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
     * blind pauses, we re-locate the element and retry the drag (up to
     * MAX_DRAG_ATTEMPTS total) until its position actually changes,
     * logging a warning if every attempt comes back as a no-op so a
     * genuine app/locator regression is still visible instead of just
     * quietly failing the caller's own before/after assertion.
     */
    private static final int MAX_DRAG_ATTEMPTS = 3;

    private void dragWithRetry(By locator, int totalX, int totalY) {
        WebElement box = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", box);
        HumanActions.pause();

        // Page-relative (not box.getLocation()) so a browser auto-scroll
        // triggered mid-drag (e.g. dragging near the viewport edge) can't
        // masquerade as element movement — see getPageRelativeLocation().
        Point before = getPageRelativeLocation(locator);
        boolean expectedMovement = (totalX != 0 || totalY != 0);

        Point after = before;
        for (int attempt = 1; attempt <= MAX_DRAG_ATTEMPTS; attempt++) {
            WebElement target = driver.findElement(locator);
            smoothDrag(target, totalX, totalY);
            after = getPageRelativeLocation(locator);

            if (!expectedMovement || !after.equals(before)) {
                break;
            }
            logger.warn("[DraggablePage] Drag attempt " + attempt + "/" + MAX_DRAG_ATTEMPTS
                + " produced no movement for " + locator + " (still at " + after + ") — retrying");
        }
    }

    // ── Simple tab ──────────────────────────────────────────────────────────────

    public Point getDragBoxLocation() {
        return getPageRelativeLocation(simpleDragBox);
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

    public Point getXOnlyBoxLocation() { return getPageRelativeLocation(onlyXBox); }
    public Point getYOnlyBoxLocation() { return getPageRelativeLocation(onlyYBox); }

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
        return getPageRelativeLocation(wrapper).getX() + wrapper.getSize().getWidth();
    }

    public int getContainmentWrapperBottomEdge() {
        WebElement wrapper = driver.findElement(containmentWrap);
        return getPageRelativeLocation(wrapper).getY() + wrapper.getSize().getHeight();
    }

    public Point getContainedBoxLocation() { return getPageRelativeLocation(containedBox); }

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
        return getPageRelativeLocation(containedWithinParentBox);
    }

    public int getContainedWithinParentBoundaryRightEdge() {
        WebElement box = driver.findElement(containedWithinParentBox);
        WebElement parent = (WebElement) js.executeScript("return arguments[0].parentElement;", box);
        return getPageRelativeLocation(parent).getX() + parent.getSize().getWidth();
    }

    public int getContainedWithinParentBoundaryBottomEdge() {
        WebElement box = driver.findElement(containedWithinParentBox);
        WebElement parent = (WebElement) js.executeScript("return arguments[0].parentElement;", box);
        return getPageRelativeLocation(parent).getY() + parent.getSize().getHeight();
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
        return getPageRelativeLocation(locator);
    }

    public void dragCursorBox(By locator, int offsetX, int offsetY) {
        dragWithRetry(locator, offsetX, offsetY);
    }
}
