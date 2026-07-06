package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class ModalDialogsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard   = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By modalDialogsMenu  = By.xpath("//span[text()='Modal Dialogs']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By smallModalButton  = By.id("showSmallModal");
    private final By largeModalButton  = By.id("showLargeModal");

    // ── Small modal ────────────────────────────────────────────────────────────
    private final By smallModalTitle   = By.id("example-modal-sizes-title-sm");
    private final By smallModalBody    = By.cssSelector("#smallModal .modal-body");
    private final By smallModalClose   = By.id("closeSmallModal");

    // ── Large modal ────────────────────────────────────────────────────────────
    private final By largeModalTitle   = By.id("example-modal-sizes-title-lg");
    private final By largeModalBody    = By.cssSelector("#largeModal .modal-body");
    private final By largeModalClose   = By.id("closeLargeModal");

    public ModalDialogsPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
        this.js     = (JavascriptExecutor) driver;
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToModalDialogs() {
        HumanActions.click(driver, alertsFrameCard);
        HumanActions.click(driver, modalDialogsMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalButton));
    }

    // ── Small modal ────────────────────────────────────────────────────────────

    public void openSmallModal() {
        HumanActions.click(driver, smallModalButton);
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalTitle));
        HumanActions.pause();
    }

    public String getSmallModalTitle() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(smallModalTitle)
        ).getText();
    }

    public String getSmallModalBody() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(smallModalBody)
        ).getText();
    }

    public void closeSmallModal() {
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();

        WebElement btn = wait.until(
                ExpectedConditions.elementToBeClickable(smallModalClose)
        );
        js.executeScript("arguments[0].click();", btn);

        wait.until(
                ExpectedConditions.invisibilityOfElementLocated(smallModalTitle)
        );
    }

    // ── Large modal ────────────────────────────────────────────────────────────

    public void openLargeModal() {
        HumanActions.click(driver, largeModalButton);
        wait.until(ExpectedConditions.visibilityOfElementLocated(largeModalTitle));
        HumanActions.pause();
    }

    public String getLargeModalTitle() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(largeModalTitle)
        ).getText();
    }

    public String getLargeModalBody() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(largeModalBody)
        ).getText();
    }

    public void closeLargeModal() {
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();

        WebElement btn = wait.until(
                ExpectedConditions.elementToBeClickable(largeModalClose)
        );
        js.executeScript("arguments[0].click();", btn);

        wait.until(
                ExpectedConditions.invisibilityOfElementLocated(largeModalTitle)
        );
    }
}