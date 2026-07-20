package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.PracticeFormPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PracticeFormTest extends BaseTest {

    // ── Helper ─────────────────────────────────────────────────────────────────
    // Extracted method - creates page object and navigates to form
    // Both tests need this, so instead of repeating it, it lives here once
    private PracticeFormPage openForm() {
        PracticeFormPage page = new PracticeFormPage(getDriver());
        page.navigateToPracticeForm();
        return page;
    }

    // ── Helpers for filling sections ───────────────────────────────────────────
    // Each section of the form is its own method
    // Makes the test read like a checklist, easy to understand

    private void fillPersonalDetails(PracticeFormPage page) {
        page.enterFirstName("Amit");
        page.enterLastName("Thakor");
        page.enterEmail("amit@test.com");
        page.selectGender("male");
        page.enterMobile("9876543210");
    }

    private void fillAdditionalDetails(PracticeFormPage page) {
        page.selectDateOfBirth("May", "1999", "15");
        page.enterSubject("Maths");
        page.selectSports();
        page.selectReading();
    }

    private void fillAddressAndLocation(PracticeFormPage page) {
        page.enterCurrentAddress("Gandhinagar, Gujarat");
        page.selectState("Rajasthan");
        page.selectCity("Jaipur");
    }

    private void verifyModal(PracticeFormPage page, String expectedName) {
        Assert.assertTrue(
                page.isModalDisplayed(),
                "Confirmation modal should appear after submit"
        );
        Assert.assertEquals(
                page.getModalTitle(),
                "Thanks for submitting the form"
        );
        Assert.assertTrue(
                page.getModalContent().contains(expectedName),
                "Modal should show submitted name: " + expectedName
        );
        page.closeModal();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Practice Form - Submit With All Fields")
    public void verifyFormSubmissionWithAllFields() {
        PracticeFormPage page = openForm();

        fillPersonalDetails(page);
        fillAdditionalDetails(page);

        // Upload picture - change path to a real file on your system
        String picturePath = System.getProperty("user.dir") + "/target/test-upload.txt";
        java.io.File pictureFile = new java.io.File(picturePath);
        if (!pictureFile.exists()) {
            pictureFile.getParentFile().mkdirs();
            try { pictureFile.createNewFile(); } catch (java.io.IOException ignored) {}
        }
        page.uploadPicture(picturePath);

        fillAddressAndLocation(page);
        page.submitForm();
        verifyModal(page, "Amit");
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Practice Form - Submit With Mandatory Fields Only")
    public void verifyFormSubmissionWithMandatoryFieldsOnly() {
        PracticeFormPage page = openForm();

        // Only mandatory fields - first name, last name, gender, mobile
        page.enterFirstName("Test");
        page.enterLastName("User");
        page.selectGender("female");
        page.enterMobile("9876543210");

        page.submitForm();
        verifyModal(page, "Test");
    }
}