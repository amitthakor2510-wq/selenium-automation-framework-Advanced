package com.automation.sites.demoqa.pages;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class LinksPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By linksMenu    = By.xpath("//span[text()='Links']");

    // ── Simple links (open new tab) ────────────────────────────────────────────
    private final By homeLink        = By.id("simpleLink");
    private final By dynamicHomeLink = By.id("dynamicLink");

    // ── API call links (send HTTP request, show response) ─────────────────────
    private final By createdLink      = By.id("created");
    private final By noContentLink    = By.id("no-content");
    private final By movedLink        = By.id("moved");
    private final By badRequestLink   = By.id("bad-request");
    private final By unauthorizedLink = By.id("unauthorized");
    private final By forbiddenLink    = By.id("forbidden");
    private final By notFoundLink     = By.id("invalid-url");

    // ── Response message shown after API link click ────────────────────────────
    private final By apiResponse = By.id("linkResponse");

    public LinksPage(WebDriver driver) {
        this.driver = driver;
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToLinks() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, linksMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(homeLink));
    }

    // ── Simple link actions ────────────────────────────────────────────────────

    /**
     * Clicks the Home link, switches to the new tab that opens,
     * grabs the URL, closes the new tab, switches back to original tab.
     */
    public String clickHomeLinkAndGetNewTabUrl() {
        HumanActions.click(driver, homeLink);
        return switchToNewTabAndGetUrl();
    }

    public String clickDynamicHomeLinkAndGetNewTabUrl() {
        HumanActions.click(driver, dynamicHomeLink);
        return switchToNewTabAndGetUrl();
    }

    private String switchToNewTabAndGetUrl() {
        // Store original tab handle
        String originalTab = driver.getWindowHandle();

        // Wait until a second tab opens
        wait.until(ExpectedConditions.numberOfWindowsToBe(2));

        // Switch to the new tab
        for (String tab : driver.getWindowHandles()) {
            if (!tab.equals(originalTab)) {
                driver.switchTo().window(tab);
                break;
            }
        }

        HumanActions.pause(); // pause so you can see the new tab
        String url = driver.getCurrentUrl();

        // Close new tab and switch back
        driver.close();
        driver.switchTo().window(originalTab);

        return url;
    }

    // ── API link actions ───────────────────────────────────────────────────────

    public String clickCreatedLink() {
        HumanActions.click(driver, createdLink);
        return getApiResponseText();
    }

    public String clickNoContentLink() {
        HumanActions.click(driver, noContentLink);
        return getApiResponseText();
    }

    public String clickMovedLink() {
        HumanActions.click(driver, movedLink);
        return getApiResponseText();
    }

    public String clickBadRequestLink() {
        HumanActions.click(driver, badRequestLink);
        return getApiResponseText();
    }

    public String clickUnauthorizedLink() {
        HumanActions.click(driver, unauthorizedLink);
        return getApiResponseText();
    }

    public String clickForbiddenLink() {
        HumanActions.click(driver, forbiddenLink);
        return getApiResponseText();
    }

    public String clickNotFoundLink() {
        HumanActions.click(driver, notFoundLink);
        return getApiResponseText();
    }

    private String getApiResponseText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(apiResponse)
        ).getText();
    }
}