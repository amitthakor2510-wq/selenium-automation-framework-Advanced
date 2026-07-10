package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.DroppablePage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DroppableTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Droppable - Simple Drag And Drop Changes Drop Zone Text")
    public void verifySimpleDragAndDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.dragToDropZone();

        String text = page.getSimpleDropText();
        System.out.println("Drop zone text: " + text);

        Assert.assertEquals(text, "Dropped!", "Drop zone should say 'Dropped!'");
    }

    @Test(priority = 2, groups = {"regression"},
            description = "Droppable - Acceptable Element Is Accepted")
    public void verifyAcceptableDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickAcceptTab();
        page.dragAcceptableToDropZone();

        String text = page.getAcceptDropText();
        System.out.println("Accept tab drop text: " + text);

        Assert.assertEquals(text, "Dropped!", "Acceptable element should be accepted");
    }

    @Test(priority = 3, groups = {"regression"},
            description = "Droppable - Not Acceptable Element Is Rejected")
    public void verifyNotAcceptableDrop() {
        DroppablePage page = new DroppablePage(getDriver());
        page.navigateToDroppable();
        page.clickAcceptTab();
        page.dragNotAcceptableToDropZone();

        String text = page.getAcceptDropText();
        System.out.println("After not-acceptable drop text: " + text);

        Assert.assertNotEquals(text, "Dropped!", "Non-acceptable element should be rejected");
    }
}