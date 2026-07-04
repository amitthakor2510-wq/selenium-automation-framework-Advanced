package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.TextBoxPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TextBoxTest extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "Text Box - Fill and Submit Form")
    public void fillTextBoxForm() {
        TextBoxPage textBoxPage = new TextBoxPage(getDriver());

        textBoxPage.navigateToTextBox();
        textBoxPage.fillForm("Amit", "amit@test.com", "Bangalore", "India");

        Assert.assertTrue(textBoxPage.getOutputName().contains("Amit"));
    }
}
