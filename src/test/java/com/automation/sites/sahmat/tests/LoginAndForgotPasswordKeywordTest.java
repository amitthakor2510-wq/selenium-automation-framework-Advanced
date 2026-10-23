package com.automation.sites.sahmat.tests;

import com.automation.core.keyword.KeywordReader;
import com.automation.sites.core.KeywordTestBase;
import io.qameta.allure.Feature;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Every SAHMAT Login + Forgot Password scenario, driven entirely by
 * {@code src/test/resources/testdata/keyword/SAHMAT_login_forgot_password_keywords.csv}
 * and resolved against locators in
 * {@code src/test/resources/objectrepository/SAHMAT.properties}.
 *
 * This class is written ONCE and should never need editing again for
 * ordinary changes:
 *   - New scenario?            Add a new testCase block to the CSV.
 *   - Site markup/ids changed? Edit SAHMAT.properties (and/or let
 *                               SelfHealingEngine heal it automatically -
 *                               every CLICK/TYPE/etc. keyword already
 *                               routes through it, see KeywordEngine).
 *   - Different assertion?     Change the CSV row's keyword/expected -
 *                               the full vocabulary is in Keyword.java.
 *
 * The {@link #scenarios()} DataProvider reads every distinct testCase
 * name straight out of the CSV at run time, so this single @Test method
 * automatically picks up new scenarios the next time it runs - nobody
 * has to add a matching Java method for each one (contrast with
 * KeywordDrivenLoginTest, which hand-lists one @Test per testCase; this
 * class intentionally does not, so it truly never needs a code change).
 */
@Feature("SAHMAT Authentication - Keyword Driven")
public class LoginAndForgotPasswordKeywordTest extends KeywordTestBase {

    private static final String OBJECT_REPO = "objectrepository/SAHMAT.properties";
    private static final String SCRIPT = "src/test/resources/testdata/keyword/SAHMAT_login_forgot_password_keywords.csv";

    @DataProvider(name = "SAHMATScenarios")
    public Object[][] scenarios() {
        return KeywordReader.readAll(SCRIPT).keySet().stream()
            .map(testCase -> new Object[]{testCase})
            .toArray(Object[][]::new);
    }

    @Test(groups = {"smoke", "regression", "keyword-driven"}, dataProvider = "SAHMATScenarios",
        description = "SAHMAT Login/Forgot-Password - runs every scenario defined in the keyword CSV")
    public void runScenario(String testCase) {
        runKeywordTestCase(OBJECT_REPO, SCRIPT, testCase);
    }
}
