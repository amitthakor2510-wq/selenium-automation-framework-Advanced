package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class AccordianPage extends BasePage {

    // ✅ CORRECT locators for DemoQA Accordion
    // Section headers (the clickable part)
    private final By section1Header = By.cssSelector("#accordianContainer > div > div:nth-child(1) > h2 > button");
    private final By section2Header = By.cssSelector("#accordianContainer > div > div:nth-child(2) > h2 > button");
    private final By section3Header = By.cssSelector("#accordianContainer > div > div:nth-child(3) > h2 > button");

    /*
     * ROOT CAUSE FIX — Content locators
     *
     * The old code used By.id("section1Content") / By.id("section2Content") / By.id("section3Content").
     * These IDs DO NOT EXIST in demoQA's current React page (confirmed by NoSuchElementException
     * in logs for section2Content and section3Content, and the false-return from isSectionVisible
     * for section1Content even when the section is open).
     *
     * The actual DOM structure is a Bootstrap accordion:
     *
     *   #accordianContainer
     *     └── div.accordion                        (the container div)
     *           ├── div.accordion-item              (nth-child 1 = Section 1)
     *           │     ├── h2.accordion-header > button.accordion-button   ← header
     *           │     └── div.accordion-collapse.collapse[.show]          ← content wrapper
     *           │           └── div.accordion-body                        ← actual text
     *           ├── div.accordion-item              (nth-child 2 = Section 2)
     *           └── div.accordion-item              (nth-child 3 = Section 3)
     *
     * The content div is the SIBLING of the h2 inside each accordion item.
     * It has class "accordion-collapse collapse" and Bootstrap adds/removes "show"
     * to expand/collapse it — same mechanism as before, just different selector to find it.
     *
     * Selector strategy: nth-child on the accordion-item, then find the collapse div inside it.
     * Using :nth-child on the outer wrapper and then descendant .accordion-collapse is robust
     * because it doesn't depend on any id attribute that might not exist.
     */
    private final By section1Content = By.cssSelector(
        "#accordianContainer > div > div:nth-child(1) > div");
    private final By section2Content = By.cssSelector(
        "#accordianContainer > div > div:nth-child(2) > div");
    private final By section3Content = By.cssSelector(
        "#accordianContainer > div > div:nth-child(3) > div");

    public AccordianPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    public void navigateToAccordian() {
        navigateTo("/accordian");
        wait.until(ExpectedConditions.visibilityOfElementLocated(section1Header));
        HumanActions.pause();
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    /**
     * Waits for a section's collapse div to gain the "show" class and for
     * Bootstrap's animation ("collapsing") to finish.
     *
     * Using presenceOfElementLocated (not visibilityOfElementLocated) because
     * collapsed sections may have height:0 and would fail a visibility check
     * even when present in DOM.
     */
    private void waitForSectionOpen(By contentLocator) {
        WebElement content = wait.until(
            ExpectedConditions.presenceOfElementLocated(contentLocator));
        wait.until(ExpectedConditions.attributeContains(content, "class", "show"));
        wait.until(d -> {
            String cls = content.getAttribute("class");
            return cls != null && !cls.contains("collapsing");
        });
    }

    /**
     * Correct visibility check: the element must have "show" class AND
     * must NOT be mid-animation ("collapsing").
     * Does NOT use isDisplayed() because Bootstrap collapses via height:0,
     * not display:none, so isDisplayed() returns true even when collapsed.
     */
    private boolean isSectionVisible(By contentLocator) {
        try {
            WebElement el = wait.until(
                ExpectedConditions.presenceOfElementLocated(contentLocator));
            String cls = el.getAttribute("class");
            return cls != null && cls.contains("show") && !cls.contains("collapsing");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Waits for the close animation to finish: both "show" and "collapsing"
     * must be absent before we assert the section is hidden.
     */
    private void waitForSectionClose(By contentLocator) {
        wait.until(d -> {
            try {
                WebElement el = d.findElement(contentLocator);
                String cls = el.getAttribute("class");
                return cls != null && !cls.contains("show") && !cls.contains("collapsing");
            } catch (Exception e) {
                return true;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 1
    // ═══════════════════════════════════════════════════════════════════════

    public String getSection1HeaderText() {
        return wait.until(
            ExpectedConditions.visibilityOfElementLocated(section1Header)
        ).getText().trim();
    }

    public boolean isSection1ContentVisible() {
        return isSectionVisible(section1Content);
    }

    public void clickSection1Header() {
        scrollAndJsClick(section1Header);
        waitForSectionClose(section1Content);
        HumanActions.pause();
    }

    public String getSection1Content() {
        try {
            return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section1Content)
            ).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 2
    // ═══════════════════════════════════════════════════════════════════════

    public void openSection2() {
        scrollAndJsClick(section2Header);
        waitForSectionOpen(section2Content);
        HumanActions.pause();
    }

    public boolean isSection2ContentVisible() {
        return isSectionVisible(section2Content);
    }

    public void clickSection2Header() {
        scrollAndJsClick(section2Header);
        waitForSectionClose(section2Content);
        HumanActions.pause();
    }

    public String getSection2Content() {
        try {
            return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section2Content)
            ).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public String getSection2HeaderText() {
        return wait.until(
            ExpectedConditions.visibilityOfElementLocated(section2Header)
        ).getText().trim();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 3
    // ═══════════════════════════════════════════════════════════════════════

    public void openSection3() {
        scrollAndJsClick(section3Header);
        waitForSectionOpen(section3Content);
        HumanActions.pause();
    }

    public boolean isSection3ContentVisible() {
        return isSectionVisible(section3Content);
    }

    public void clickSection3Header() {
        scrollAndJsClick(section3Header);
        waitForSectionClose(section3Content);
        HumanActions.pause();
    }

    public String getSection3Content() {
        try {
            return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section3Content)
            ).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public String getSection3HeaderText() {
        return wait.until(
            ExpectedConditions.visibilityOfElementLocated(section3Header)
        ).getText().trim();
    }
}
