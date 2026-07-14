package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class AccordianPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By widgetsCard   = By.xpath("//h5[text()='Widgets']");
    private final By accordianMenu = By.xpath("//span[text()='Accordian']");

    // ── Section headers ────────────────────────────────────────────────────────
    private final By section1Header = By.xpath(
            "//h2[contains(@class,'accordion-header')][1]//button"
    );
    private final By section2Header = By.xpath(
            "/html/body/div/div/div/div/div[2]/div[1]/div/div[2]/h2/button"
    );
    private final By section3Header = By.xpath(
            "/html/body/div/div/div/div/div[2]/div[1]/div/div[3]/h2/button"
    );

    // ── Section content ────────────────────────────────────────────────────────
    private final By section1Content = By.xpath(
            "//div[contains(@class,'accordion-collapse') and contains(@class,'show')]//p"
    );
    private final By section2Content = By.xpath(
            "//div[@id='section2Content']//p | " +
                    "//div[contains(@class,'accordion-collapse')][2]//p"
    );
    private final By section3Content = By.xpath(
            "//div[@id='section3Content']//p | " +
                    "//div[contains(@class,'accordion-collapse')][3]//p"
    );

    private final By firstHeading = By.xpath(
            "//h2[contains(@class,'accordion')]//button | " +
                    "//div[contains(@class,'accordion-button')] | " +
                    "//div[@id='accordianWrapper']"
    );

    public AccordianPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToAccordian() {
        HumanActions.click(driver, widgetsCard);
        HumanActions.click(driver, accordianMenu);
        wait.until(ExpectedConditions.urlContains("accordian"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//div[contains(@class,'accordion')]")
        ));
        HumanActions.pause();
    }

    // ── Section 1 ─────────────────────────────────────────────────────────────

    public String getSection1HeaderText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section1Header)
        ).getText().trim();
    }

    public String getSection1Content() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(section1Content)
        ).getText().trim();
    }

    // ── Section 2 ─────────────────────────────────────────────────────────────

    public void openSection2() {
        WebElement header = wait.until(
                ExpectedConditions.presenceOfElementLocated(section2Header)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", header);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", header);
        wait.until(d ->
                !d.findElements(By.xpath(
                        "/html/body/div/div/div/div/div[2]/div[1]/div/div[2]/h2/button"
                )).isEmpty()
        );
        HumanActions.pause();
    }

    public String getSection2Content() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.xpath(
                                "//div[contains(@class,'accordion-collapse') " +
                                        "and contains(@class,'show')]//p"
                        )
                )
        ).getText().trim();
    }

    // ── Section 3 ─────────────────────────────────────────────────────────────

    public void openSection3() {
        WebElement header = wait.until(
                ExpectedConditions.presenceOfElementLocated(section3Header)
        );
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", header);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", header);
        wait.until(d ->
                !d.findElements(By.xpath(
                        "/html/body/div/div/div/div/div[2]/div[1]/div/div[3]/h2/button"
                )).isEmpty()
        );
        HumanActions.pause();
    }

    public String getSection3Content() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.xpath(
                                "//div[contains(@class,'accordion-collapse') " +
                                        "and contains(@class,'show')]//p"
                        )
                )
        ).getText().trim();
    }
}