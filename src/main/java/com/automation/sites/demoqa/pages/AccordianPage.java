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

    // Section content divs (the expandable content)
    private final By section1Content = By.id("section1Content");
    private final By section2Content = By.id("section2Content");
    private final By section3Content = By.id("section3Content");

    // Constructor
    public AccordianPage(WebDriver driver) {
        super(driver);
    }

    // Navigate to Accordian page
    public void navigateToAccordian() {
        navigateTo("/accordian");
        wait.until(ExpectedConditions.visibilityOfElementLocated(section1Header));
        HumanActions.pause();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 1 Methods
    // ═══════════════════════════════════════════════════════════════════════

    public String getSection1HeaderText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section1Header)
        ).getText().trim();
    }

    public String getSection1Content() {
        try {
            WebElement content = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(section1Content)
            );
            return content.getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isSection1ContentVisible() {
        try {
            WebElement element = driver.findElement(section1Content);
            return element.isDisplayed() && element.getAttribute("class").contains("show");
        } catch (Exception e) {
            return false;
        }
    }

    public void clickSection1Header() {
        scrollAndJsClick(section1Header);
        HumanActions.pause();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 2 Methods
    // ═══════════════════════════════════════════════════════════════════════

    public void openSection2() {
        scrollAndJsClick(section2Header);
        wait.until(ExpectedConditions.attributeContains(
                driver.findElement(section2Content), "class", "show")
        );
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

    public boolean isSection2ContentVisible() {
        try {
            WebElement element = driver.findElement(section2Content);
            return element.isDisplayed() && element.getAttribute("class").contains("show");
        } catch (Exception e) {
            return false;
        }
    }

    public void clickSection2Header() {
        scrollAndJsClick(section2Header);
        HumanActions.pause();
    }

    public String getSection2HeaderText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section2Header)
        ).getText().trim();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Section 3 Methods
    // ═══════════════════════════════════════════════════════════════════════

    public void openSection3() {
        scrollAndJsClick(section3Header);
        wait.until(ExpectedConditions.attributeContains(
                driver.findElement(section3Content), "class", "show")
        );
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

    public boolean isSection3ContentVisible() {
        try {
            WebElement element = driver.findElement(section3Content);
            return element.isDisplayed() && element.getAttribute("class").contains("show");
        } catch (Exception e) {
            return false;
        }
    }

    public void clickSection3Header() {
        scrollAndJsClick(section3Header);
        HumanActions.pause();
    }

    public String getSection3HeaderText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section3Header)
        ).getText().trim();
    }
}