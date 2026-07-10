package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.SortablePage;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class SortableTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Sortable - List Has 6 Items By Default")
    public void verifyListHasSixItems() {
        SortablePage page = new SortablePage(getDriver());
        page.navigateToSortable();

        List<String> items = page.getListItemTexts();
        System.out.println("List items: " + items);

        Assert.assertEquals(items.size(), 6, "Sortable list should have 6 items");
    }

    @Test(priority = 2, groups = {"regression"},
            description = "Sortable - Drag First Item To Third Position Changes Order")
    public void verifyDragChangesOrder() {
        SortablePage page = new SortablePage(getDriver());
        page.navigateToSortable();

        List<String> before = page.getListItemTexts();
        System.out.println("Before drag: " + before);

        page.dragListItem(0, 2);

        List<String> after = page.getListItemTexts();
        System.out.println("After drag:  " + after);

        Assert.assertNotEquals(before, after, "List order should change after drag");
    }

    @Test(priority = 3, groups = {"regression"},
            description = "Sortable - Grid Tab Has 9 Items")
    public void verifyGridHasNineItems() {
        SortablePage page = new SortablePage(getDriver());
        page.navigateToSortable();
        page.clickGridTab();

        List<String> items = page.getGridItemTexts();
        System.out.println("Grid items: " + items);

        Assert.assertEquals(items.size(), 9, "Sortable grid should have 9 items");
    }

    @Test(priority = 4, groups = {"regression"},
            description = "Sortable - Grid Is Draggable")
    public void verifyGridIsDraggable() {
        SortablePage page = new SortablePage(getDriver());
        page.navigateToSortable();
        page.clickGridTab();

        boolean draggable = page.gridIsDragable();
        System.out.println("Grid draggable: " + draggable);
        Assert.assertTrue(draggable, "Grid should be draggable");
    }
}