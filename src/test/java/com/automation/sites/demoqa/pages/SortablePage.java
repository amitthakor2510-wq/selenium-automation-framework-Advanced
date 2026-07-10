package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class SortablePage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By sortableMenu     = By.xpath("//span[text()='Sortable']");
    private final By listTab          = By.id("demo-tab-list");
    private final By gridTab          = By.id("demo-tab-grid");
    private final By listItems        = By.cssSelector("#demo-tabpane-list .list-group-item");
    private final By gridItems        = By.cssSelector("#demo-tabpane-grid .list-group-item");

    public SortablePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToSortable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", card);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", card);

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(sortableMenu));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(listItems));
        WebElement first = driver.findElement(listItems);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", first);
        HumanActions.pause();
    }

    public List<String> getListItemTexts() {
        return driver.findElements(listItems)
                .stream()
                .map(e -> e.getText().trim())
                .collect(Collectors.toList());
    }

    /**
     * Drags list item at fromIndex onto item at toIndex.
     *
     * NEW CONCEPT — clickAndHold + moveToElement + release:
     * This keeps the mouse button pressed the entire time so the browser
     * sees a continuous drag. Breaking into separate perform() calls
     * would release the mouse between moves and cancel the drag.
     */
    public void dragListItem(int fromIndex, int toIndex) {
        List<String> before = getListItemTexts();
        List<WebElement> items = driver.findElements(listItems);
        if (fromIndex < 0 || fromIndex >= items.size() || toIndex < 0 || toIndex >= items.size()) {
            throw new IndexOutOfBoundsException("Invalid from/to indices for dragListItem");
        }
        WebElement source = items.get(fromIndex);
        WebElement target = items.get(toIndex);

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", source);
        HumanActions.pause();

        // Compute center points and move by offset from source center to target center for reliable drags
        org.openqa.selenium.Point sLoc = source.getLocation();
        org.openqa.selenium.Dimension sSize = source.getSize();
        int sCenterX = sLoc.getX() + sSize.getWidth() / 2;
        int sCenterY = sLoc.getY() + sSize.getHeight() / 2;

        org.openqa.selenium.Point tLoc = target.getLocation();
        org.openqa.selenium.Dimension tSize = target.getSize();
        int tCenterX = tLoc.getX() + tSize.getWidth() / 2;
        int tCenterY = tLoc.getY() + tSize.getHeight() / 2;

        int deltaX = tCenterX - sCenterX;
        int deltaY = tCenterY - sCenterY;

        try {
            // For vertical lists, dragging by vertical pixel offset is more reliable
            int itemHeight = source.getSize().getHeight();
            int steps = toIndex - fromIndex;
            int dragY = steps * itemHeight;
            new Actions(driver).dragAndDropBy(source, 0, dragY).perform();
        } catch (Exception ex) {
            // fallback to center-to-center move if dragBy fails
            new Actions(driver)
                    .moveToElement(source)
                    .clickAndHold()
                    .pause(Duration.ofMillis(200))
                    .moveByOffset(deltaX, deltaY)
                    .pause(Duration.ofMillis(200))
                    .release()
                    .perform();
        }

        // Wait briefly for order to update (some implementations animate). If order unchanged, try a small retry.
        try {
            wait.until(d -> !getListItemTexts().equals(before));
        } catch (Exception e) {
            // Retry once with a slight offset tweak
            new Actions(driver)
                    .moveToElement(source)
                    .clickAndHold()
                    .moveByOffset(deltaX + 5, deltaY + 5)
                    .release()
                    .perform();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        HumanActions.pause();
    }

    public void clickGridTab() {
        HumanActions.click(driver, gridTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(gridItems));
        HumanActions.pause();
    }

    public List<String> getGridItemTexts() {
        return driver.findElements(gridItems)
                .stream()
                .map(e -> e.getText().trim())
                .collect(Collectors.toList());
    }

    /**
     * Returns true if items in the grid can be reordered by dragging.
     * This method will attempt a small non-destructive drag (0 -> 1) and
     * determine whether the order changed. If the order is changed it will
     * attempt to restore the original order before returning.
     */
    public boolean gridIsDragable() {
        // Ensure grid tab is active
        HumanActions.click(driver, gridTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(gridItems));

        List<String> before = getGridItemTexts();
        if (before.size() < 2) {
            return false;
        }

        boolean changed = false;
        try {
            // Attempt a single small reorder: move index 0 -> 1
            dragGridItem(0, 1);
            List<String> after = getGridItemTexts();
            changed = !after.equals(before);
            // If changed, try to restore original order by moving the item back
            if (changed) {
                // After moving 0->1, the original item usually sits at index 1; move it back to 0
                dragGridItem(1, 0);
                // Wait for the original order to be restored (best-effort)
                try {
                    wait.until(d -> getGridItemTexts().equals(before));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // If any error occurs, treat as not draggable
            try {
                // best-effort restore: if something partially changed, attempt to revert
                List<String> now = getGridItemTexts();
                if (!now.equals(before) && now.size() >= 2) {
                    // try to move item at index 1 back to 0
                    try { dragGridItem(1, 0); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return false;
        }

        return changed;
    }

    public void dragGridItem(int fromIndex, int toIndex) {
        List<String> before = getGridItemTexts();
        List<WebElement> items = driver.findElements(gridItems);
        if (fromIndex < 0 || fromIndex >= items.size() || toIndex < 0 || toIndex >= items.size()) {
            throw new IndexOutOfBoundsException("Invalid from/to indices for dragGridItem");
        }
        WebElement source = items.get(fromIndex);
        WebElement target = items.get(toIndex);

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", source);
        HumanActions.pause();

        org.openqa.selenium.Point sLoc = source.getLocation();
        org.openqa.selenium.Dimension sSize = source.getSize();
        int sCenterX = sLoc.getX() + sSize.getWidth() / 2;
        int sCenterY = sLoc.getY() + sSize.getHeight() / 2;

        org.openqa.selenium.Point tLoc = target.getLocation();
        org.openqa.selenium.Dimension tSize = target.getSize();
        int tCenterX = tLoc.getX() + tSize.getWidth() / 2;
        int tCenterY = tLoc.getY() + tSize.getHeight() / 2;

        int deltaX = tCenterX - sCenterX;
        int deltaY = tCenterY - sCenterY;

        new Actions(driver)
                .moveToElement(source)
                .clickAndHold()
                .pause(Duration.ofMillis(200))
                .moveByOffset(deltaX, deltaY)
                .pause(Duration.ofMillis(200))
                .release()
                .perform();

        try {
            wait.until(d -> !getGridItemTexts().equals(before));
        } catch (Exception e) {
            new Actions(driver)
                    .moveToElement(source)
                    .clickAndHold()
                    .moveByOffset(deltaX + 5, deltaY + 5)
                    .release()
                    .perform();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        HumanActions.pause();
    }
}