package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class BrokenLinksImagesPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard       = By.xpath("//h5[text()='Elements']");
    private final By brokenLinksMenu    = By.xpath("//span[text()='Broken Links - Images']");

    // ── Images ─────────────────────────────────────────────────────────────────
    // Both images are plain <img> tags — located by their src attribute
    private final By validImage         = By.xpath("//img[@src='/images/Toolsqa.jpg']");
    private final By brokenImage        = By.xpath("//img[@src='/images/Toolsqa_1.jpg']");

    // ── Links ──────────────────────────────────────────────────────────────────
    private final By validLink          = By.linkText("Click Here for Valid Link");
    private final By brokenLink         = By.linkText("Click Here for Broken Link");

    public BrokenLinksImagesPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToBrokenLinksImages() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, brokenLinksMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(validImage));
    }

    // ── Image checks ───────────────────────────────────────────────────────────

    /**
     * A valid image loads completely. We check this using JavaScript
     * because Selenium has no built-in way to check if an image loaded.
     * naturalWidth > 0 means the image loaded successfully.
     * naturalWidth = 0 means the image is broken / failed to load.
     */
    public boolean isValidImageLoaded() {
        WebElement img = wait.until(
                ExpectedConditions.presenceOfElementLocated(validImage)
        );
        HumanActions.pause();
        return isImageLoaded(img);
    }

    public boolean isBrokenImageLoaded() {
        WebElement img = wait.until(
                ExpectedConditions.presenceOfElementLocated(brokenImage)
        );
        HumanActions.pause();
        return isImageLoaded(img);
    }

    /**
     * Uses JavaScript to check the naturalWidth of the image.
     * naturalWidth is a browser property:
     *   > 0 = image loaded fine
     *   = 0 = image is broken
     */
    private boolean isImageLoaded(WebElement imgElement) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(
                "return arguments[0].naturalWidth", imgElement
        );
        long width = (result instanceof Long) ? (Long) result : 0L;
        return width > 0;
    }

    // ── Link checks ────────────────────────────────────────────────────────────

    /**
     * Clicks the valid link, waits for navigation,
     * returns the current URL so the test can verify it.
     */
    public String clickValidLinkAndGetUrl() {
        HumanActions.click(driver, validLink);
        wait.until(ExpectedConditions.not(
                ExpectedConditions.urlContains("broken")
        ));
        HumanActions.pause();
        return driver.getCurrentUrl();
    }

    /**
     * Clicks the broken link, waits for page to load,
     * returns the current URL.
     * The broken link goes to a 500 error page.
     */
    public String clickBrokenLinkAndGetUrl() {
        HumanActions.click(driver, brokenLink);
        wait.until(ExpectedConditions.not(
                ExpectedConditions.urlContains("broken")
        ));
        HumanActions.pause();
        return driver.getCurrentUrl();
    }
}