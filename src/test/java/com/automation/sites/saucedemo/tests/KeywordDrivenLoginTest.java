package com.automation.sites.saucedemo.tests;

import com.automation.sites.core.KeywordTestBase;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

/**
 * Same login scenarios as LoginDataDrivenTest, but expressed as
 * keyword-driven scripts instead of Java + a data file: every step
 * (navigate, type, click, verify...) lives in
 * src/test/resources/testdata/keyword/saucedemo_login_keywords.csv,
 * resolved against locators in
 * src/test/resources/objectrepository/saucedemo.properties.
 *
 * Adding a new scenario is a new block of rows in that CSV — no new
 * Java method required unless it needs assertions the keyword
 * vocabulary doesn't already cover (see Keyword.java).
 */
@Feature("Authentication")
@Story("Login - Keyword Driven")
public class KeywordDrivenLoginTest extends KeywordTestBase {

    private static final String OBJECT_REPO = "objectrepository/saucedemo.properties";
    private static final String SCRIPT = "src/test/resources/testdata/keyword/saucedemo_login_keywords.csv";

    @Test(groups = {"regression", "keyword-driven"},
            description = "SauceDemo - Valid login, driven entirely by keyword script")
    public void validLogin() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC01_ValidLogin");
    }

    @Test(groups = {"regression", "keyword-driven"},
            description = "SauceDemo - Locked-out user shows error, driven entirely by keyword script")
    public void lockedOutLoginShowsError() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC02_InvalidLogin");
    }

    @Test(groups = {"regression", "keyword-driven", "keyboard-driven"},
            description = "SauceDemo - Login completed using only the keyboard (Tab + Enter, no clicks on the fields)")
    public void keyboardOnlyLogin() {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC03_KeyboardOnlyLogin");
    }
}