package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.TextBoxPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TextBoxTest extends BaseTest {

    @Test(groups = {"smoke", "regression"})
    public void fillTextBoxForm() {
        TextBoxPage textBoxPage = new TextBoxPage(getDriver());

        textBoxPage.navigateToTextBox();
        textBoxPage.fillForm("Amit", "amit@test.com", "Bangalore", "India");

        Assert.assertTrue(textBoxPage.getOutputName().contains("Amit"));
    }
}
