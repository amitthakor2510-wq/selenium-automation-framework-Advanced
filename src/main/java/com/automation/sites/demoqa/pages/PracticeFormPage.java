package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class PracticeFormPage extends BasePage {

    // ── Text fields ────────────────────────────────────────────────────────────
    private final By firstName      = By.id("firstName");
    private final By lastName       = By.id("lastName");
    private final By email          = By.id("userEmail");
    private final By mobile         = By.id("userNumber");
    private final By currentAddress = By.id("currentAddress");

    // ── Gender ─────────────────────────────────────────────────────────────────
    private final By genderMaleLabel   = By.xpath("//label[@for='gender-radio-1']");
    private final By genderFemaleLabel = By.xpath("//label[@for='gender-radio-2']");
    private final By genderOtherLabel  = By.xpath("//label[@for='gender-radio-3']");

    // ── Date of birth ──────────────────────────────────────────────────────────
    private final By dateOfBirthInput = By.id("dateOfBirthInput");
    private final By monthSelect      = By.className("react-datepicker__month-select");
    private final By yearSelect       = By.className("react-datepicker__year-select");

    // ── Hobbies ────────────────────────────────────────────────────────────────
    private final By sportsLabel  = By.xpath("//label[@for='hobbies-checkbox-1']");
    private final By readingLabel = By.xpath("//label[@for='hobbies-checkbox-2']");
    private final By musicLabel   = By.xpath("//label[@for='hobbies-checkbox-3']");

    // ── Upload ─────────────────────────────────────────────────────────────────
    private final By uploadPicture = By.id("uploadPicture");

    // ── Subjects ───────────────────────────────────────────────────────────────
    private final By subjectsInput = By.id("subjectsInput");

    // ── State and City ─────────────────────────────────────────────────────────
    private final By stateDropdown = By.id("react-select-3-input");
    private final By cityDropdown  = By.id("react-select-4-input");

    // ── Submit ─────────────────────────────────────────────────────────────────
    private final By submitButton = By.id("submit");

    // ── Modal ──────────────────────────────────────────────────────────────────
    private final By modalTitle       = By.id("example-modal-sizes-title-lg");
    private final By modalTableBody   = By.cssSelector(".table-responsive tbody");
    private final By modalCloseButton = By.id("closeLargeModal");

    public PracticeFormPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToPracticeForm() {
        navigateTo("/automation-practice-form");
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstName));
        HumanActions.pause();
    }

    // ── Text fields ────────────────────────────────────────────────────────────

    public void enterFirstName(String name) {
        HumanActions.type(driver, firstName, name);
    }

    public void enterLastName(String name) {
        HumanActions.type(driver, lastName, name);
    }

    public void enterEmail(String emailAddress) {
        HumanActions.type(driver, email, emailAddress);
    }

    public void enterMobile(String mobileNumber) {
        HumanActions.type(driver, mobile, mobileNumber);
    }

    public void enterCurrentAddress(String address) {
        HumanActions.type(driver, currentAddress, address);
    }

    // ── Gender ─────────────────────────────────────────────────────────────────

    public void selectGender(String gender) {
        switch (gender.toLowerCase()) {
            case "male":
                scrollAndClick(genderMaleLabel);
                break;
            case "female":
                scrollAndClick(genderFemaleLabel);
                break;
            case "other":
                scrollAndClick(genderOtherLabel);
                break;
            default:
                throw new RuntimeException("Invalid gender: " + gender);
        }
    }

    // ── Date of Birth ──────────────────────────────────────────────────────────

    public void selectDateOfBirth(String month, String year, String day) {
        HumanActions.click(driver, dateOfBirthInput);
        wait.until(ExpectedConditions.visibilityOfElementLocated(monthSelect));
        HumanActions.pause();

        // Month and year are plain HTML selects - Select class works fine
        new Select(driver.findElement(monthSelect)).selectByVisibleText(month);
        HumanActions.pause();

        new Select(driver.findElement(yearSelect)).selectByVisibleText(year);
        HumanActions.pause();

        // Click the correct day - exclude only days from other months
        By dayLocator = By.xpath(
                "//div[contains(@class,'react-datepicker__day')" +
                        " and not(contains(@class,'outside-month'))" +
                        " and text()='" + day + "']"
        );
        wait.until(ExpectedConditions.elementToBeClickable(dayLocator));
        HumanActions.click(driver, dayLocator);
    }

    // ── Subjects ───────────────────────────────────────────────────────────────

    public void enterSubject(String subject) {
        WebElement input = wait.until(
                ExpectedConditions.elementToBeClickable(subjectsInput)
        );
        HumanActions.pause();
        input.sendKeys(subject);

        // Wait for autocomplete to appear then press ENTER to select
        // More reliable than clicking the option element
        By suggestion = By.xpath(
                "//div[contains(@class,'subjects-auto-complete__option')]"
        );
        wait.until(ExpectedConditions.visibilityOfElementLocated(suggestion));
        HumanActions.pause();
        input.sendKeys(Keys.ENTER);
    }

    // ── Hobbies ────────────────────────────────────────────────────────────────

    public void selectSports()  { scrollAndClick(sportsLabel);  }
    public void selectReading() { scrollAndClick(readingLabel); }
    public void selectMusic()   { scrollAndClick(musicLabel);   }

    // ── Upload ─────────────────────────────────────────────────────────────────

    public void uploadPicture(String filePath) {
        WebElement input = wait.until(
                ExpectedConditions.presenceOfElementLocated(uploadPicture)
        );
        HumanActions.pause();
        input.sendKeys(filePath);
    }

    // ── State and City ─────────────────────────────────────────────────────────

    public void selectState(String state) {
        selectReactDropdown(stateDropdown, state);
    }

    public void selectCity(String city) {
        wait.until(ExpectedConditions.elementToBeClickable(cityDropdown));
        selectReactDropdown(cityDropdown, city);
    }

    /**
     * Fixed react-select approach:
     * Type the value → wait for any option to appear →
     * press ENTER to select the first match.
     * This avoids class name issues entirely because
     * ENTER always selects whatever is highlighted,
     * regardless of which version of react-select is used.
     */
    private void selectReactDropdown(By inputLocator, String value) {
        WebElement input = wait.until(
                ExpectedConditions.elementToBeClickable(inputLocator)
        );

        // Scroll into center so ad banner doesn't block it
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", input
        );
        HumanActions.pause();

        // Clear any existing value then type
        input.clear();
        input.sendKeys(value);
        HumanActions.pause();

        // Wait for dropdown options to appear
        // Using a broad locator that works regardless of react-select version
        By anyOption = By.xpath(
                "//div[contains(@id,'react-select') and contains(@id,'option')]"
        );
        wait.until(ExpectedConditions.visibilityOfElementLocated(anyOption));
        HumanActions.pause();

        // Press ENTER to select the highlighted/first option
        input.sendKeys(Keys.ENTER);
        HumanActions.pause();
    }

    // ── Submit ─────────────────────────────────────────────────────────────────

    public void submitForm() {
        js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
        HumanActions.pause();

        WebElement btn = wait.until(
                ExpectedConditions.presenceOfElementLocated(submitButton)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", btn
        );
        HumanActions.pause();
        js.executeScript("arguments[0].click();", btn);
    }

    // ── Modal ──────────────────────────────────────────────────────────────────

    public boolean isModalDisplayed() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(modalTitle)
        ).isDisplayed();
    }

    public String getModalTitle() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(modalTitle)
        ).getText();
    }

    public String getModalContent() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(modalTableBody)
        ).getText();
    }

    /**
     * Fixed closeModal:
     * 1. Scroll modal into center view
     * 2. Wait until close button is visible AND clickable
     * 3. JS click to bypass any overlay
     * 4. Wait for modal to fully disappear from DOM
     */
    public void closeModal() {
        // Scroll to top so modal is fully in view
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();

        // Wait until modal is visible
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(modalTitle)
        );

        // Try 3 different ways to close — stops at whichever works first
        boolean closed = false;

        // Attempt 1 — JS click on close button
        try {
            WebElement closeBtn = driver.findElement(modalCloseButton);
            js.executeScript("arguments[0].click();", closeBtn);
            HumanActions.pause();

            // Check if modal disappeared
            if (driver.findElements(modalTitle).isEmpty()
                    || !driver.findElement(modalTitle).isDisplayed()) {
                closed = true;
            }
        } catch (Exception ignored) {}

        // Attempt 2 — Press Escape key to dismiss modal
        if (!closed) {
            try {
                driver.findElement(By.tagName("body"))
                        .sendKeys(Keys.ESCAPE);
                HumanActions.pause();

                if (driver.findElements(modalTitle).isEmpty()
                        || !driver.findElement(modalTitle).isDisplayed()) {
                    closed = true;
                }
            } catch (Exception ignored) {}
        }

        // Attempt 3 — Click outside the modal (the backdrop)
        if (!closed) {
            try {
                js.executeScript(
                        "document.querySelector('.modal-backdrop').click();"
                );
                HumanActions.pause();
            } catch (Exception ignored) {}
        }

        // Final wait — just confirm modal is gone, don't throw if already gone
        try {
            new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)))
                    .until(ExpectedConditions.invisibilityOfElementLocated(modalTitle));
        } catch (Exception ignored) {
            // Modal may have already closed via one of the attempts above
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private void scrollAndClick(By locator) {
        WebElement element = wait.until(
                ExpectedConditions.presenceOfElementLocated(locator)
        );
        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});", element
        );
        HumanActions.pause();
        js.executeScript("arguments[0].click();", element);
    }
}