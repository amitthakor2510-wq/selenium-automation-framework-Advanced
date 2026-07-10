package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.DraggablePage;
import org.openqa.selenium.Point;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DraggableTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Draggable - Simple Box Moves To New Position")
    public void verifySimpleDrag() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();

        Point before = page.getDragBoxLocation();
        System.out.println("Before: " + before);

        page.dragSimpleBoxBy(150, 80);

        Point after = page.getDragBoxLocation();
        System.out.println("After:  " + after);

        Assert.assertNotEquals(before, after, "Box position should change after drag");
    }

    @Test(priority = 2, groups = {"regression"},
            description = "Draggable - X-Only Box Does Not Move Vertically")
    public void verifyXAxisRestriction() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickAxisTab();

        Point before = page.getXOnlyBoxLocation();
        System.out.println("X-only before: " + before);

        page.dragXOnlyBox(100);

        Point after = page.getXOnlyBoxLocation();
        System.out.println("X-only after:  " + after);

        Assert.assertNotEquals(after.getX(), before.getX(), "X should change");
        Assert.assertEquals(after.getY(), before.getY(), "Y should NOT change for X-restricted box");
    }

    @Test(priority = 3, groups = {"regression"},
            description = "Draggable - Y-Only Box Does Not Move Horizontally")
    public void verifyYAxisRestriction() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickAxisTab();

        Point before = page.getYOnlyBoxLocation();
        System.out.println("Y-only before: " + before);

        page.dragYOnlyBox(100);

        Point after = page.getYOnlyBoxLocation();
        System.out.println("Y-only after:  " + after);

        Assert.assertEquals(after.getX(), before.getX(), "X should NOT change for Y-restricted box");
        Assert.assertNotEquals(after.getY(), before.getY(), "Y should change");
    }

    @Test(priority = 4, groups = {"regression"},
            description = "Draggable - Container Restricted Box Stays Within Parent")
    public void verifyContainerRestriction() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickContainerTab();

        // Try to drag far right and down — jQuery UI should clamp to wrapper boundary
        page.dragContainedBoxBy(400, 200);

        Point boxPos        = page.getContainedBoxLocation();
        int   wrapperRight  = page.getContainmentWrapperRightEdge();
        int   wrapperBottom = page.getContainmentWrapperBottomEdge();

        System.out.println("Box position:      " + boxPos);
        System.out.println("Wrapper right edge: " + wrapperRight);
        System.out.println("Wrapper bottom edge:" + wrapperBottom);

        Assert.assertTrue(boxPos.getX() <= wrapperRight,
                "Box left edge should not exceed wrapper right edge");
        Assert.assertTrue(boxPos.getY() <= wrapperBottom,
                "Box top edge should not exceed wrapper bottom edge");
    }

    @Test(priority = 5, groups = {"regression"},
            description = "Draggable - Cursor Center Box Drags Right")
    public void verifyCursorCenter() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        String cursor = page.getCursorStyle(page.getCursorCenterLocator());
        System.out.println("Cursor Center style: " + cursor);
        Assert.assertEquals(cursor, "move");

        page.dragCursorBox(page.getCursorCenterLocator(), 150, 0);   // → right
    }

    @Test(priority = 6, groups = {"regression"},
            description = "Draggable - Cursor TopLeft Box Drags Diagonally")
    public void verifyCursorTopLeft() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        String cursor = page.getCursorStyle(page.getCursorTopLeftLocator());
        System.out.println("Cursor TopLeft style: " + cursor);
        Assert.assertEquals(cursor, "move");

        page.dragCursorBox(page.getCursorTopLeftLocator(), 100, 80);  // → diagonal down-right
    }

    @Test(priority = 7, groups = {"regression"},
            description = "Draggable - Cursor Bottom Box Drags Down")
    public void verifyCursorBottom() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        String cursor = page.getCursorStyle(page.getCursorBottomLocator());
        System.out.println("Cursor Bottom style: " + cursor);
        Assert.assertEquals(cursor, "move");

        page.dragCursorBox(page.getCursorBottomLocator(), 0, 100);    // ↓ straight down
    }
}