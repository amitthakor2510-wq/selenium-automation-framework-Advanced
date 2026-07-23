package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class ModalDialogsPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard  = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By modalDialogsMenu = By.xpath("//span[text()='Modal Dialogs']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By smallModalButton = By.id("showSmallModal");
    private final By largeModalButton = By.id("showLargeModal");

    // ── Small modal ────────────────────────────────────────────────────────────
    private final By smallModalTitle = By.id("example-modal-sizes-title-sm");
    private final By smallModalBody  = By.cssSelector(".modal-body");
    private final By smallModalClose = By.id("closeSmallModal");

    // ── Large modal ────────────────────────────────────────────────────────────
    private final By largeModalTitle = By.id("example-modal-sizes-title-lg");
    private final By largeModalBody  = By.cssSelector(".modal-body");
    private final By largeModalClose = By.id("closeLargeModal");

    public ModalDialogsPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToModalDialogs() {
        navigateTo("/modal-dialogs");
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalButton));
    }

    // ── Small modal ────────────────────────────────────────────────────────────

    public void openSmallModal() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.click(driver, smallModalButton);
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalTitle));
        HumanActions.pause();
    }

    public String getSmallModalTitle() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalTitle)).getText();
    }

    public String getSmallModalBody() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalTitle));
        HumanActions.pause();
        return driver.findElement(smallModalBody).getText().trim();
    }

    public void closeSmallModal() {
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(smallModalClose));
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(smallModalTitle));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.pause();
    }

    // ── Large modal ────────────────────────────────────────────────────────────

    public void openLargeModal() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.click(driver, largeModalButton);
        wait.until(ExpectedConditions.visibilityOfElementLocated(largeModalTitle));
        HumanActions.pause();
    }

    public String getLargeModalTitle() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(largeModalTitle)).getText();
    }

    public String getLargeModalBody() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(largeModalTitle));
        HumanActions.pause();
        return driver.findElement(largeModalBody).getText().trim();
    }

    public void closeLargeModal() {
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(largeModalClose));
        js.executeScript("arguments[0].click();", btn);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(largeModalTitle));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.pause();
    }
}
