package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.DroppablePage;
import org.openqa.selenium.Point;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DroppableTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Droppable - Simple Drag And Drop Shows Dropped")
    public void verifySimpleDragAndDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.dragToDropZone();

        String text = page.getSimpleDropText();
        System.out.println("Simple drop text: " + text);
        Assert.assertEquals(text, "Dropped!", "Drop zone should say 'Dropped!'");
    }

    @Test(priority = 2, groups = {"regression"},
        description = "Droppable - Acceptable Element Is Accepted By Drop Zone")
    public void verifyAcceptableDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickAcceptTab();
        page.dragAcceptableToDropZone();

        String text = page.getAcceptDropText();
        System.out.println("Accept drop text: " + text);
        Assert.assertEquals(text, "Dropped!", "Acceptable element should be accepted");
    }

    @Test(priority = 3, groups = {"regression"},
        description = "Droppable - Dragging Away From Drop Zone Does Not Trigger Drop")
    public void verifyDragAwayDoesNotDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickAcceptTab();
        // Try dropping the NOT-ACCEPTABLE element into the drop zone — it should NOT be accepted
        page.dragNotAcceptableToDropZone();

        String text = page.getAcceptDropText();
        System.out.println("Drop text after attempting to drop not-acceptable: " + text);
        Assert.assertNotEquals(text, "Dropped!", "Not-acceptable element should NOT be accepted by the drop zone");
    }

    @Test(priority = 4, groups = {"regression"},
        description = "Droppable - Drop On Inner Not-Greedy Box Also Triggers Outer")
    public void verifyNotGreedyPropagation() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickPreventPropTab();
        page.dragToInnerNotGreedy();

        String outer = page.getOuterNotGreedyText();
        String inner = page.getInnerNotGreedyText();
        System.out.println("Outer not-greedy text: " + outer);
        System.out.println("Inner not-greedy text: " + inner);

        Assert.assertEquals(inner, "Dropped!", "Inner not-greedy should show Dropped!");
        Assert.assertEquals(outer, "Dropped!", "Outer should also show Dropped! (event propagated)");
    }

    @Test(priority = 5, groups = {"regression"},
        description = "Droppable - Drop On Inner Greedy Box Does NOT Trigger Outer")
    public void verifyGreedyPreventsOuterDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickPreventPropTab();
        page.dragToInnerGreedy();

        String outer = page.getOuterGreedyText();
        String inner = page.getInnerGreedyText();
        System.out.println("Outer greedy text: " + outer);
        System.out.println("Inner greedy text: " + inner);

        Assert.assertEquals(inner, "Dropped!", "Inner greedy should show Dropped!");
        Assert.assertNotEquals(outer, "Dropped!", "Outer should NOT show Dropped! (event prevented)");
    }

    @Test(priority = 6, groups = {"regression"},
        description = "Droppable - Will Revert Box Returns To Original Position After Drop")
    public void verifyWillRevert() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickRevertDraggableTab();

        Point before = page.getWillRevertBoxLocation();
        System.out.println("Will-revert before: " + before);

        page.dragWillRevertToDropZone();

        Point after = page.getWillRevertBoxLocation();
        System.out.println("Will-revert after:  " + after);

        Assert.assertEquals(after.getX(), before.getX(), "X should revert to original");
        Assert.assertEquals(after.getY(), before.getY(), "Y should revert to original");
    }

    @Test(priority = 7, groups = {"regression"},
        description = "Droppable - Not Revert Box Stays At Drop Position")
    public void verifyNotRevert() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickRevertDraggableTab();

        Point before = page.getNotRevertBoxLocation();
        System.out.println("Not-revert before: " + before);

        page.dragNotRevertToDropZone();

        Point after = page.getNotRevertBoxLocation();
        System.out.println("Not-revert after:  " + after);

        Assert.assertNotEquals(after, before, "Not-revert box should stay at drop position");
    }
}
