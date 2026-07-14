package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class BrokenLinksImagesPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard    = By.xpath("//h5[text()='Elements']");
    private final By brokenLinksMenu = By.xpath("//span[text()='Broken Links - Images']");

    // ── Images ─────────────────────────────────────────────────────────────────
    private final By validImage  = By.xpath("//img[@src='/images/Toolsqa.jpg']");
    private final By brokenImage = By.xpath("//img[@src='/images/Toolsqa_1.jpg']");

    // ── Links ──────────────────────────────────────────────────────────────────
    private final By validLink  = By.linkText("Click Here for Valid Link");
    private final By brokenLink = By.linkText("Click Here for Broken Link");

    public BrokenLinksImagesPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToBrokenLinksImages() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, brokenLinksMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(validImage));
    }

    public boolean isValidImageLoaded() {
        WebElement img = wait.until(ExpectedConditions.presenceOfElementLocated(validImage));
        HumanActions.pause();
        return isImageLoaded(img);
    }

    public boolean isBrokenImageLoaded() {
        WebElement img = wait.until(ExpectedConditions.presenceOfElementLocated(brokenImage));
        HumanActions.pause();
        return isImageLoaded(img);
    }

    private boolean isImageLoaded(WebElement imgElement) {
        Object result = js.executeScript("return arguments[0].naturalWidth", imgElement);
        long width = (result instanceof Long) ? (Long) result : 0L;
        return width > 0;
    }

    public String clickValidLinkAndGetUrl() {
        HumanActions.click(driver, validLink);
        wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("broken")));
        HumanActions.pause();
        return driver.getCurrentUrl();
    }

    public String clickBrokenLinkAndGetUrl() {
        HumanActions.click(driver, brokenLink);
        wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("broken")));
        HumanActions.pause();
        return driver.getCurrentUrl();
    }
}