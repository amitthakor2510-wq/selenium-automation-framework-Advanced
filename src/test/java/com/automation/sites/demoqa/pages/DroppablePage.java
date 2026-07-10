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

public class DroppablePage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    private final By interactionsCard = By.xpath("//h5[text()='Interactions']");
    private final By droppableMenu    = By.xpath("//span[text()='Droppable']");

    private final By simpleTab      = By.id("droppableExample-tab-simple");
    private final By simpleDragBox  = By.cssSelector("#simpleDropContainer #draggable");
    private final By simpleDropBox  = By.cssSelector("#simpleDropContainer #droppable");
    private final By simpleDropText = By.cssSelector("#simpleDropContainer #droppable p");

    private final By acceptTab         = By.id("droppableExample-tab-accept");
    private final By acceptableDrag    = By.id("acceptable");
    private final By notAcceptableDrag = By.id("notAcceptable");
    private final By acceptDropBox     = By.cssSelector("#acceptDropContainer #droppable");
    private final By acceptDropText    = By.cssSelector("#acceptDropContainer #droppable p");

    public DroppablePage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    public void navigateToDroppable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", card);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", card);

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(droppableMenu));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDragBox));
        WebElement drag = driver.findElement(simpleDragBox);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();
    }

    /**
     * Drags the draggable box onto the drop zone.
     *
     * NEW CONCEPT — dragAndDrop(source, target):
     * Shorthand for clickAndHold → moveToElement → release.
     * Use when dragging one element directly onto another element.
     * Use clickAndHold + moveByOffset when you need pixel-level control instead.
     */
    public void dragToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDragBox));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDropBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();

        new Actions(driver).dragAndDrop(drag, drop).perform();
        HumanActions.pause();
    }

    public String getSimpleDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(simpleDropText)).getText().trim();
    }

    public void clickAcceptTab() {
        HumanActions.click(driver, acceptTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        HumanActions.pause();
    }

    public void dragAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDropBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();

        new Actions(driver).dragAndDrop(drag, drop).perform();
        HumanActions.pause();
    }

    public void dragNotAcceptableToDropZone() {
        WebElement drag = wait.until(ExpectedConditions.visibilityOfElementLocated(notAcceptableDrag));
        WebElement drop = wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDropBox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", drag);
        HumanActions.pause();

        new Actions(driver).dragAndDrop(drag, drop).perform();
        HumanActions.pause();
    }

    public String getAcceptDropText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(acceptDropText)).getText().trim();
    }
}