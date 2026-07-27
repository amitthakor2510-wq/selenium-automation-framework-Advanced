package com.automation.sites.demoqa.tests;

import com.automation.sites.core.KeywordTestBase;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

/**
 * Same Text Box scenarios TextBoxTest already covers in Java, but expressed
 * as keyword-driven scripts instead — every step (navigate, type, click,
 * verify...) lives in
 * src/test/resources/testdata/keyword/demoqa_textbox_keywords.csv, resolved
 * against locators in src/test/resources/objectrepository/demoqa.properties.
 *
 * This is the same pattern KeywordDrivenLoginTest already established for
 * saucedemo — ported to demoqa here so non-Java team members can author new
 * demoqa scenarios as CSV rows too, without needing a second engine.
 *
 * NOTE on TC02_KeyboardOnlyFill: it submits via PRESS_KEY(ENTER) on the
 * email field rather than clicking the Submit button, on the assumption
 * that demoqa's Text Box form responds to Enter the same way a native HTML
 * form would. That assumption is UNVERIFIED against the live site as of
 * writing — if this test fails at step 5, that's the first thing to check;
 * swap it for a CLICK on demoqa.textbox.submitButton if Enter turns out not
 * to trigger submission.
 */
@Feature("Text Box")
public class KeywordDrivenTextBoxTest extends KeywordTestBase {

    private static final String OBJECT_REPO = "objectrepository/demoqa.properties";
    private static final String SCRIPT = "src/test/resources/testdata/keyword/demoqa_textbox_keywords.csv";

    @Test(groups = {"regression", "keyword-driven"},
        description = "DemoQA - Submit a valid Text Box form, driven entirely by keyword script")
    @Story("Text Box - Keyword Driven")
    public void submitValidForm() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC01_SubmitValidForm");
    }

    @Test(groups = {"regression", "keyword-driven", "keyboard-driven"},
        description = "DemoQA - Fill and submit the Text Box form using only the keyboard")
    @Story("Text Box - Keyword Driven")
    public void keyboardOnlyFill() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC02_KeyboardOnlyFill");
    }
}
