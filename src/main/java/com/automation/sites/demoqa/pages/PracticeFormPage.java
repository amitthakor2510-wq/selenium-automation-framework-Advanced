package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.components.BootstrapModalComponent;
import com.automation.core.components.ReactDatePickerComponent;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

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
    private final BootstrapModalComponent submissionModal;

    // Date-of-birth picking now lives in ReactDatePickerComponent — see its own javadoc for
    // why (the same widget DatePickerPage uses standalone; this page's copy used to hand-roll
    // the identical month/year/day sequence without that class's SmartLocator fallback
    // resilience).
    private final ReactDatePickerComponent dateOfBirthPicker;

    public PracticeFormPage(WebDriver driver) {
        super(driver);
        this.submissionModal = new BootstrapModalComponent(driver, wait,
            By.id("example-modal-sizes-title-lg"), By.cssSelector(".table-responsive tbody"),
            By.id("closeLargeModal"));
        this.dateOfBirthPicker = new ReactDatePickerComponent(driver, wait, dateOfBirthInput,
            By.className("react-datepicker__month-select"), By.cssSelector("select[aria-label='Month']"),
            By.className("react-datepicker__year-select"), By.cssSelector("select[aria-label='Year']"));
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
        dateOfBirthPicker.selectDate(month, year, day);
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
    // Delegates to BootstrapModalComponent — see docs/architecture.md
    // #-component-based-page-objects. Behavior is unchanged: same
    // title/body-tbody/close-button locators, same hardened 3-attempt close.

    public boolean isModalDisplayed() {
        return submissionModal.isDisplayed();
    }

    public String getModalTitle() {
        return submissionModal.getTitle();
    }

    public String getModalContent() {
        return submissionModal.getBody();
    }

    public void closeModal() {
        submissionModal.closeHardened();
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
