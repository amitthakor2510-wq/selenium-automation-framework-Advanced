package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DroppablePage extends BasePage {

    // ── Navigation ──────────────────────────────────────────────
    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By droppableMenu    = By.xpath("//span[text()='Droppable']");

    // ── Tabs ────────────────────────────────────────────────────
    private final By acceptTab          = By.id("droppableExample-tab-accept");
    private final By preventPropTab     = By.id("droppableExample-tab-preventPropogation");
    private final By revertDraggableTab = By.id("droppableExample-tab-revertable");

    // ── Simple tab ───────────────────────────────────────────────
    private final By simpleDrag     = By.cssSelector("#droppableExample-tabpane-simple #draggable");
    private final By simpleDrop     = By.cssSelector("#droppableExample-tabpane-simple #droppable");
    private final By simpleDropText = By.cssSelector("#droppableExample-tabpane-simple #droppable p");

    // ── Accept tab ───────────────────────────────────────────────
    private final By acceptableDrag    = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[@id='acceptable']");
    private final By notAcceptableDrag = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drag-box') and not(@id='acceptable')]");
    private final By acceptDrop        = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]");
    private final By acceptDropText    = By.xpath("//div[@id='droppableExample-tabpane-accept']//div[contains(@class,'drop-box')]/p");

    // ── Prevent Propagation tab ─────────────────────────────────
    private final By preventDrag        = By.cssSelector("#droppableExample-tabpane-preventPropogation #dragBox");
    private final By innerNotGreedy     = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox");
    private final By innerGreedy        = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner");
    private final By outerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyDropBox > p");
    private final By innerNotGreedyText = By.cssSelector("#droppableExample-tabpane-preventPropogation #notGreedyInnerDropBox > p");
    private final By outerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBox > p");
    private final By innerGreedyText    = By.cssSelector("#droppableExample-tabpane-preventPropogation #greedyDropBoxInner > p");

    // ── Revert Draggable tab ─────────────────────────────────────
    private final By willRevertDrag = By.cssSelector("#droppableExample-tabpane-revertable #revertable");
    private final By notRevertDrag  = By.cssSelector("#droppableExample-tabpane-revertable #notRevertable");
    private final By revertDrop     = By.cssSelector("#droppableExample-tabpane-revertable #droppable");
    private final By revertDropText = By.cssSelector("#droppableExample-tabpane-revertable #droppable p");

    public DroppablePage(WebDriver driver) {
        super(driver); // BasePage sets driver, wait (from config), js
    }

    // ── Navigation ───────────────────────────────────────────────

    public void navigateToDroppable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        scrollAndJsClick(card);
        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(droppableMenu));
        scrollAndJsClick(menu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
    }

    // ── Simple tab ───────────────────────────────────────────────

    public void dragToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3))
                        .until(ExpectedConditions.textToBe(simpleDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        if (!success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            HumanActions.pause();
        }
    }

    public String getSimpleDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDropText))
                .getText().trim();
    }

    // ── Accept tab ───────────────────────────────────────────────

    public void clickAcceptTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(acceptTab));
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-accept"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        HumanActions.pause();
    }

    public void dragAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        new Actions(driver).dragAndDrop(drag, drop).build().perform();
        HumanActions.pause();
    }

    public void dragNotAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notAcceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        new Actions(driver).dragAndDrop(drag, drop).build().perform();
        HumanActions.pause();
    }

    public void dragAcceptableAwayFromDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        new Actions(driver)
                .clickAndHold(drag)
                .moveByOffset(300, 0)
                .release()
                .build()
                .perform();
        HumanActions.pause();
    }

    public String getAcceptDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDropText))
                .getText().trim();
    }

    // ── Prevent Propagation tab ─────────────────────────────────

    public void clickPreventPropTab() {
        WebElement tab = wait.until(ExpectedConditions.elementToBeClickable(preventPropTab));
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-preventPropogation"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        HumanActions.pause();
    }

    public void dragToInnerNotGreedy() {
        WebElement drag  = wait.until(ExpectedConditions.visibilityOfElementLocated(preventDrag));
        WebElement inner = wait.until(ExpectedConditions.visibilityOfElementLocated(innerNotGreedy));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
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
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
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
        js.executeScript("arguments[0].click();", tab);
        wait.until(d -> {
            WebElement pane = d.findElement(By.id("droppableExample-tabpane-revertable"));
            String cls = pane.getAttribute("class");
            return cls != null && cls.contains("active") && cls.contains("show");
        });
        wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
        HumanActions.pause();
    }

    public void dragWillRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(willRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3))
                        .until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        HumanActions.pause();
    }

    public void dragNotRevertToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notRevertDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(revertDrop));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            new Actions(driver).dragAndDrop(drag, drop).build().perform();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3))
                        .until(ExpectedConditions.textToBe(revertDropText, "Dropped!"));
                success = true;
            } catch (Exception e) {
                attempts++;
                HumanActions.pause();
            }
        }
        HumanActions.pause();
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
}