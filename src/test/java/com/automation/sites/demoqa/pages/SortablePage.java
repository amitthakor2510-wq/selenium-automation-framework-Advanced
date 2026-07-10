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
        List<WebElement> items = driver.findElements(listItems);
        WebElement source = items.get(fromIndex);
        WebElement target = items.get(toIndex);

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", source);
        HumanActions.pause();

        new Actions(driver)
                .clickAndHold(source)
                .pause(Duration.ofMillis(500))
                .moveToElement(target)
                .pause(Duration.ofMillis(500))
                .release()
                .perform();

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

    public void dragGridItem(int fromIndex, int toIndex) {
        List<WebElement> items = driver.findElements(gridItems);
        WebElement source = items.get(fromIndex);
        WebElement target = items.get(toIndex);

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", source);
        HumanActions.pause();

        new Actions(driver)
                .clickAndHold(source)
                .pause(Duration.ofMillis(500))
                .moveToElement(target)
                .pause(Duration.ofMillis(500))
                .release()
                .perform();

        HumanActions.pause();
    }
}