package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrokenLinksImagesPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(BrokenLinksImagesPage.class);

    // The failure resolved img.complete=true with naturalWidth=0 in ~6s (well
    // under the 10s poll deadline) — not a slow/timed-out load, a genuine
    // failed fetch. That points to demoqa/toolsqa's own image host being
    // flaky (transient CDN/hotlink hiccup) rather than our wait logic. Retry
    // with a fresh page load a couple of times before treating it as real.
    private static final int MAX_LOAD_ATTEMPTS = 3;

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
        navigateTo("/broken");
        wait.until(ExpectedConditions.visibilityOfElementLocated(validImage));
    }

    public boolean isValidImageLoaded() {
        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            WebElement img = wait.until(ExpectedConditions.presenceOfElementLocated(validImage));
            if (waitForImageLoaded(img)) {
                return true;
            }
            logger.info("Valid image failed to load (attempt " + attempt + "/" + MAX_LOAD_ATTEMPTS + ")");
            if (attempt < MAX_LOAD_ATTEMPTS) {
                // Fresh navigation gives the browser a new fetch attempt at
                // the image instead of re-polling a request that already
                // finished (successfully or not).
                navigateToBrokenLinksImages();
            }
        }
        return false;
    }

    public boolean isBrokenImageLoaded() {
        WebElement img = wait.until(ExpectedConditions.presenceOfElementLocated(brokenImage));
        return waitForImageLoaded(img);
    }

    /**
     * presenceOfElementLocated only confirms the <img> tag exists in the DOM —
     * it says nothing about whether the browser has actually finished
     * downloading and decoding the image. naturalWidth legitimately stays 0
     * until that completes, so reading it right after presence (even with a
     * short human.pause()) is a race condition: it passes when the image
     * happens to load fast enough and fails when the network/CDN is slow,
     * with no code change involved either way. Poll naturalWidth (or
     * "definitely never going to load", via .complete + naturalWidth===0)
     * directly instead of guessing at a fixed delay.
     */
    private boolean waitForImageLoaded(WebElement imgElement) {
        try {
            // Returns null (= keep polling) until the browser reports img.complete;
            // after that the value is the final naturalWidth, loaded or genuinely failed.
            Long width = new WebDriverWait(driver, Duration.ofSeconds(10))
                .pollingEvery(Duration.ofMillis(100))
                .until(d -> {
                    Object result = js.executeScript(
                        "var img = arguments[0];" +
                            "if (img.complete) { return img.naturalWidth; }" +
                            "return null;",
                        imgElement
                    );
                    return result instanceof Number ? ((Number) result).longValue() : null;
                });
            return width > 0;
        } catch (TimeoutException e) {
            // Browser never reported img.complete at all - treat as "did not load".
            return false;
        }
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
