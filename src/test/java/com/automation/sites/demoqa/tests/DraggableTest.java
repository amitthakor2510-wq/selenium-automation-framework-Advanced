package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.DraggablePage;
import org.openqa.selenium.By;
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
            description = "Draggable - Contained-Within-Parent Box Stays Within Its Immediate Parent")
    public void verifyContainedWithinParentRestriction() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickContainerTab();

        // Distinct from #containmentWrapper — this box is constrained to its own
        // immediate parent element, not the wrapper used by verifyContainerRestriction.
        page.dragContainedWithinParentBoxBy(200, 150);

        Point boxPos       = page.getContainedWithinParentLocation();
        int   parentRight  = page.getContainedWithinParentBoundaryRightEdge();
        int   parentBottom = page.getContainedWithinParentBoundaryBottomEdge();

        System.out.println("Parent-contained box position: " + boxPos);
        System.out.println("Parent right edge:  " + parentRight);
        System.out.println("Parent bottom edge: " + parentBottom);

        Assert.assertTrue(boxPos.getX() <= parentRight,
                "Box left edge should not exceed its parent's right edge");
        Assert.assertTrue(boxPos.getY() <= parentBottom,
                "Box top edge should not exceed its parent's bottom edge");
    }

    @Test(priority = 6, groups = {"regression"},
            description = "Draggable - Cursor Center Box Drags Right")
    public void verifyCursorCenter() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        By locator = page.getCursorCenterLocator();
        String cursor = page.getCursorStyle(locator);
        System.out.println("Cursor Center style: " + cursor);
        Assert.assertEquals(cursor, "move");

        Point before = page.getCursorBoxLocation(locator);
        page.dragCursorBox(locator, 150, 0);   // → right
        Point after = page.getCursorBoxLocation(locator);
        System.out.println("Cursor Center before/after: " + before + " -> " + after);

        Assert.assertNotEquals(before, after, "Cursor Center box should move after drag");
    }

    @Test(priority = 7, groups = {"regression"},
            description = "Draggable - Cursor TopLeft Box Drags Diagonally")
    public void verifyCursorTopLeft() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        By locator = page.getCursorTopLeftLocator();
        String cursor = page.getCursorStyle(locator);
        System.out.println("Cursor TopLeft style: " + cursor);
        Assert.assertEquals(cursor, "move");

        Point before = page.getCursorBoxLocation(locator);
        page.dragCursorBox(locator, 100, 80);  // → diagonal down-right
        Point after = page.getCursorBoxLocation(locator);
        System.out.println("Cursor TopLeft before/after: " + before + " -> " + after);

        Assert.assertNotEquals(before, after, "Cursor TopLeft box should move after drag");
    }

    @Test(priority = 8, groups = {"regression"},
            description = "Draggable - Cursor Bottom Box Drags Down")
    public void verifyCursorBottom() {
        DraggablePage page = new DraggablePage(getDriver());
        page.navigateToDraggable();
        page.clickCursorStyleTab();

        By locator = page.getCursorBottomLocator();
        String cursor = page.getCursorStyle(locator);
        System.out.println("Cursor Bottom style: " + cursor);
        Assert.assertEquals(cursor, "move");

        Point before = page.getCursorBoxLocation(locator);
        page.dragCursorBox(locator, 0, 100);    // ↓ straight down
        Point after = page.getCursorBoxLocation(locator);
        System.out.println("Cursor Bottom before/after: " + before + " -> " + after);

        Assert.assertNotEquals(before, after, "Cursor Bottom box should move after drag");
    }
}