package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class LinksPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard = By.xpath("//h5[text()='Elements']");
    private final By linksMenu    = By.xpath("//span[text()='Links']");

    // ── Simple links ───────────────────────────────────────────────────────────
    private final By homeLink        = By.id("simpleLink");
    private final By dynamicHomeLink = By.id("dynamicLink");

    // ── API call links ─────────────────────────────────────────────────────────
    private final By createdLink      = By.id("created");
    private final By noContentLink    = By.id("no-content");
    private final By movedLink        = By.id("moved");
    private final By badRequestLink   = By.id("bad-request");
    private final By unauthorizedLink = By.id("unauthorized");
    private final By forbiddenLink    = By.id("forbidden");
    private final By notFoundLink     = By.id("invalid-url");
    private final By apiResponse      = By.id("linkResponse");

    public LinksPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToLinks() {
        HumanActions.click(driver, elementsCard);
        HumanActions.click(driver, linksMenu);
        wait.until(ExpectedConditions.visibilityOfElementLocated(homeLink));
    }

    public String clickHomeLinkAndGetNewTabUrl() {
        HumanActions.click(driver, homeLink);
        return switchToNewTabAndGetUrl();
    }

    public String clickDynamicHomeLinkAndGetNewTabUrl() {
        HumanActions.click(driver, dynamicHomeLink);
        return switchToNewTabAndGetUrl();
    }

    private String switchToNewTabAndGetUrl() {
        String originalTab = driver.getWindowHandle();
        wait.until(ExpectedConditions.numberOfWindowsToBe(2));
        for (String tab : driver.getWindowHandles()) {
            if (!tab.equals(originalTab)) {
                driver.switchTo().window(tab);
                break;
            }
        }
        HumanActions.pause();
        String url = driver.getCurrentUrl();
        driver.close();
        driver.switchTo().window(originalTab);
        return url;
    }

    public String clickCreatedLink()      { HumanActions.click(driver, createdLink);      return getApiResponseText(); }
    public String clickNoContentLink()    { HumanActions.click(driver, noContentLink);    return getApiResponseText(); }
    public String clickMovedLink()        { HumanActions.click(driver, movedLink);        return getApiResponseText(); }
    public String clickBadRequestLink()   { HumanActions.click(driver, badRequestLink);   return getApiResponseText(); }
    public String clickUnauthorizedLink() { HumanActions.click(driver, unauthorizedLink); return getApiResponseText(); }
    public String clickForbiddenLink()    { HumanActions.click(driver, forbiddenLink);    return getApiResponseText(); }
    public String clickNotFoundLink()     { HumanActions.click(driver, notFoundLink);     return getApiResponseText(); }

    private String getApiResponseText() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(apiResponse)).getText();
    }
}