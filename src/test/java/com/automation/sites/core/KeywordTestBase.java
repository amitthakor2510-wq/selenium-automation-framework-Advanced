package com.automation.sites.core;

import com.automation.core.keyword.KeywordEngine;
import com.automation.core.keyword.KeywordReader;
import com.automation.core.keyword.KeywordStep;
import com.automation.core.keyword.ObjectRepository;

import java.util.List;

/**
 * Extend this instead of BaseTest for keyword-driven test classes. Adds a
 * one-line way to run an entire scripted test case from a data file:
 *
 *   public class KeywordDrivenLoginTest extends KeywordTestBase {
 *       @Test
 *       public void validLogin() {
 *           runKeywordTestCase(
 *               "objectrepository/saucedemo.properties",
 *               "src/test/resources/testdata/keyword/saucedemo_login_keywords.csv",
 *               "TC01_ValidLogin");
 *       }
 *   }
 *
 * New scenarios are new rows in the CSV/Excel file (see KeywordStep for the
 * column layout) — no new Java method needed unless the assertions differ.
 */
public class KeywordTestBase extends BaseTest {

    protected void runKeywordTestCase(String objectRepositoryResource, String scriptPath, String testCase) {
        ObjectRepository repo = ObjectRepository.load(objectRepositoryResource);
        List<KeywordStep> steps = KeywordReader.readTestCase(scriptPath, testCase);
        new KeywordEngine(getDriver(), repo).run(steps);
    }
}