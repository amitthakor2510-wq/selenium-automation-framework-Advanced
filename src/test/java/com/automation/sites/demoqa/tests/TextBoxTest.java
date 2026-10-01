package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.TextBoxPage;
import org.testng.Assert;
import org.testng.annotations.Test;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;

@Feature("Elements")
public class TextBoxTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
        description = "Text Box - Fill and Submit Form")
    @Story("Text Box Form Submission")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fill all fields in the Text Box form and verify the output section")
    public void fillTextBoxForm() {
        TextBoxPage textBoxPage = new TextBoxPage(getDriver());

        textBoxPage.navigateToTextBox();
        textBoxPage.fillForm("Amit", "amit@test.com", "Bangalore", "India");

        Assert.assertTrue(textBoxPage.getOutputName().contains("Amit"));
    }
}
