package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class SelectablePage extends BasePage {

    private final By interactionsCard  = By.xpath("//h5[text()='Interactions']");
    private final By selectableMenu    = By.xpath("//span[text()='Selectable']");
    private final By listTab           = By.id("demo-tab-list");
    private final By gridTab           = By.id("demo-tab-grid");
    private final By listItems         = By.cssSelector("#demo-tabpane-list .list-group-item");
    private final By activeListItems   = By.cssSelector("#demo-tabpane-list .list-group-item.active");
    private final By gridItems         = By.cssSelector("#demo-tabpane-grid .list-group-item");
    private final By activeGridItems   = By.cssSelector("#demo-tabpane-grid .list-group-item.active");

    public SelectablePage(WebDriver driver) {
        super(driver);
    }

    public void navigateToSelectable() {
        WebElement card = wait.until(ExpectedConditions.presenceOfElementLocated(interactionsCard));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", card);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", card);

        WebElement menu = wait.until(ExpectedConditions.presenceOfElementLocated(selectableMenu));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", menu);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", menu);

        wait.until(ExpectedConditions.visibilityOfElementLocated(listItems));
        WebElement first = driver.findElement(listItems);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", first);
        HumanActions.pause();
    }

    public void clickListItem(int index) {
        List<WebElement> items = driver.findElements(listItems);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", items.get(index));
        HumanActions.pause();
        items.get(index).click();
        HumanActions.pause();
    }

    /**
     * Selects multiple items using Ctrl+Click.
     *
     * NEW CONCEPT — keyDown / keyUp in Actions:
     * keyDown(Keys.CONTROL) holds Ctrl down for all subsequent clicks.
     * keyUp(Keys.CONTROL) releases it at the end.
     * This is exactly how a human does multi-select — hold Ctrl, click items, release.
     */
    public void ctrlClickListItems(int... indexes) {
        List<WebElement> items = driver.findElements(listItems);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", items.get(indexes[0]));
        HumanActions.pause();

        Actions actions = new Actions(driver);
        actions.keyDown(Keys.CONTROL);
        for (int idx : indexes) {
            actions.click(items.get(idx)).pause(Duration.ofMillis(200));
        }
        actions.keyUp(Keys.CONTROL).perform();
        HumanActions.pause();
    }

    public int getActiveListItemCount() {
        return driver.findElements(activeListItems).size();
    }

    public List<String> getActiveListItemTexts() {
        return driver.findElements(activeListItems)
                .stream()
                .map(e -> e.getText().trim())
                .collect(Collectors.toList());
    }

    public void clickGridTab() {
        HumanActions.click(driver, gridTab);
        wait.until(ExpectedConditions.visibilityOfElementLocated(gridItems));
        HumanActions.pause();
    }

    public void clickGridItem(int index) {
        List<WebElement> items = driver.findElements(gridItems);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", items.get(index));
        HumanActions.pause();
        items.get(index).click();
        HumanActions.pause();
    }

    public int getActiveGridItemCount() {
        return driver.findElements(activeGridItems).size();
    }
}