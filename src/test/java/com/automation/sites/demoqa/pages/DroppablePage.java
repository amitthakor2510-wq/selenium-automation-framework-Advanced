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

public class DroppablePage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ──────────────────────────────────────────────
    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By droppableMenu    = By.xpath("//span[text()='Droppable']");

    // ── Tabs ────────────────────────────────────────────────────
    private final By simpleTab          = By.id("droppableExample-tab-simple");
    private final By acceptTab          = By.id("droppableExample-tab-accept");
    private final By preventPropTab     = By.id("droppableExample-tab-preventPropogation");
    private final By revertDraggableTab = By.id("droppableExample-tab-revertable");

    // ── Simple tab ───────────────────────────────────────────────
    private final By simpleDrag     = By.cssSelector("#droppableExample-tabpane-simple #draggable");
    private final By simpleDrop     = By.cssSelector("#droppableExample-tabpane-simple #droppable");
    private final By simpleDropText = By.cssSelector("#droppableExample-tabpane-simple #droppable p");

    // ── Accept tab ───────────────────────────────────────────────
    // Use XPath to find the droppable inside the ACTIVE accept tab pane only
    private final By acceptPane     = By.id("droppableExample-tabpane-accept");
    private final By acceptableDrag = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[@id='acceptable']");
    // The second draggable in the accept pane is the "Not Acceptable" element (no id). Match the drag-box
    private final By notAcceptableDrag = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drag-box') and not(@id='acceptable')]");
    // The demoqa DOM for the Accept tab can use different element ids; target the drop box by its class
    private final By acceptDrop     = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]");
    private final By acceptDropText = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]/p");

    // ── Prevent Propagation tab ─────────────────────────────────
    private final By preventPropPane    = By.id("droppableExample-tabpane-preventPropogation");
    private final By preventDrag        = By.cssSelector("#droppableExample-tabpane-preventPropogation #dragBox");
    private final By innerNotGreedy     = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox");
    private final By innerGreedy        = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner");
    private final By outerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyDropBox > p");
    private final By innerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox > p");
    private final By outerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBox > p");
    private final By innerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner > p");

    // ── Revert Draggable tab ─────────────────────────────────────
    private final By revertPane     = By.id("droppableExample-tabpane-revertable");
    private final By willRevertDrag = By.cssSelector("#droppableExample-tabpane-revertable #revertable");
    private final By notRevertDrag  = By.cssSelector("#droppableExample-tabpane-revertable #notRevertable");
    private final By revertDrop     = By.cssSelector("#droppableExample-tabpane-revertable #droppable");
    private final By revertDropText = By.cssSelector("#droppableExample-tabpane-revertable #droppable p");

    public DroppablePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(15));
        this.js     = (JavascriptExecutor) driver;
    }

    // ── Navigation ───────────────────────────────────────────────

    public void navigateToDroppable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        scrollAndClick(card);
        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(droppableMenu));
        scrollAndClick(menu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
        // debug output removed
    }

    // ── Simple tab ───────────────────────────────────────────────

    public void dragToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrop));
        scrollIntoView(drag);
        // Retry the drag a few times if the UI misses the first attempt (some browsers/webdrivers are flaky)
        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3)).until(ExpectedConditions.textToBe(simpleDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        if (!success) {
            // final attempt with a small pause
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            HumanActions.pause();
        }
    }

    public String getSimpleDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDropText)).getText().trim();
    }

    // ── Accept tab ───────────────────────────────────────────────

    public void clickAcceptTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(acceptTab));
        scrollAndClick(tab);
        // Wait for the pane to become active then force show its contents
        wait.until(ExpectedConditions.presenceOfElementLocated(acceptPane));
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-accept"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active");
        });
        // Force visibility on the pane and its children in case of display:none
        js.executeScript(
                "var pane = document.getElementById('droppableExample-tabpane-accept');" +
                        "pane.style.display='block';" +
                        "pane.style.visibility='visible';" +
                        "pane.style.opacity='1';"
        );
        // debug output removed
        HumanActions.pause();
    }

    public void dragAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.presenceOfElementLocated(acceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.presenceOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", drag);
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", drop);
        scrollIntoView(drag);
        new Actions(driver).dragAndDrop(drag, drop).build().perform();
        HumanActions.pause();
    }

    /**
     * Attempt to drag the NOT-ACCEPTABLE element into the drop box. The test should assert that the
     * drop box does not accept this element (i.e., drop text remains not 'Dropped!').
     */
    public void dragNotAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.presenceOfElementLocated(notAcceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.presenceOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", drag);
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", drop);
        scrollIntoView(drag);
        new Actions(driver).dragAndDrop(drag, drop).build().perform();
        HumanActions.pause();
    }

    public void dragAcceptableAwayFromDropZone() {
        WebElement drag = wait.until(ExpectedConditions.presenceOfElementLocated(acceptableDrag));
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", drag);
        scrollIntoView(drag);
        new Actions(driver)
                .clickAndHold(drag)
                .moveByOffset(300, 0)
                .release()
                .build()
                .perform();
        HumanActions.pause();
    }

    public String getAcceptDropText() {
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(acceptDropText));
        js.executeScript("arguments[0].style.display='block'; arguments[0].style.visibility='visible';", el);
        return el.getText().trim();
    }

    // ── Prevent Propagation tab ─────────────────────────────────

    public void clickPreventPropTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(preventPropTab));
        scrollAndClick(tab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(preventPropPane));
        wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        // debug output removed
    }

    public void dragToInnerNotGreedy() {
        WebElement drag  = wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        WebElement inner = wait.until(ExpectedConditions.visibilityOfElementLocated(innerNotGreedy));
        scrollIntoView(drag);
        new Actions(driver)
                .clickAndHold(drag)
                .moveToElement(inner)
                .pause(Duration.ofMillis(150))
                .release()
                .build()
                .perform();
        wait.until(ExpectedConditions.textToBe(innerNotGreedyText, "Dropped!"));
        wait.until(ExpectedConditions.textToBe(outerNotGreedyText, "Dropped!"));
    }

    public void dragToInnerGreedy() {
        WebElement drag  = wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        WebElement inner = wait.until(ExpectedConditions.visibilityOfElementLocated(innerGreedy));
        scrollIntoView(drag);
        new Actions(driver)
                .clickAndHold(drag)
                .moveToElement(inner)
                .pause(Duration.ofMillis(150))
                .release()
                .build()
                .perform();
        wait.until(ExpectedConditions.textToBe(innerGreedyText, "Dropped!"));
    }

    public String getOuterNotGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(outerNotGreedyText)).getText().trim();
    }

    public String getInnerNotGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(innerNotGreedyText)).getText().trim();
    }

    public String getOuterGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(outerGreedyText)).getText().trim();
    }

    public String getInnerGreedyText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(innerGreedyText)).getText().trim();
    }

    // ── Revert Draggable tab ─────────────────────────────────────

    public void clickRevertDraggableTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(revertDraggableTab));
        scrollAndClick(tab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(revertPane));
        wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
    }

    public void dragWillRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        scrollIntoView(drag);
        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3)).until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void dragNotRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        scrollIntoView(drag);
        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3)).until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public String getRevertDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(revertDropText)).getText().trim();
    }

    public Point getWillRevertBoxLocation() {
        return driver.findElement(willRevertDrag).getLocation();
    }

    public Point getNotRevertBoxLocation() {
        return driver.findElement(notRevertDrag).getLocation();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void scrollIntoView(WebElement el) {
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        HumanActions.pause();
    }

    private void scrollAndClick(WebElement el) {
        scrollIntoView(el);
        js.executeScript("arguments[0].click();", el);
    }
}