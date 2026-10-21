package com.automation.sites.core;

import com.automation.core.keyword.KeywordReader;
import com.automation.core.keyword.KeywordStep;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Base for a site's "run every test case in this keyword script" class. It reads the script once to
 * discover the test cases and runs each as its own TestNG test, so a newly recorded or hand-written
 * test case needs no Java at all. Subclasses only say where the object repository and script are.
 */
public abstract class KeywordScriptSuiteBase extends KeywordTestBase {

    /** Classpath resource of the object repository, e.g. {@code objectrepository/saucedemo.properties}. */
    protected abstract String objectRepository();

    /** Project-relative path of the keyword script (CSV, Excel, JSON or YAML). */
    protected abstract String scriptPath();

    @DataProvider(name = "keywordTestCases")
    public Object[][] keywordTestCases() {
        Map<String, List<KeywordStep>> testCases = KeywordReader.readAll(scriptPath());
        Object[][] rows = new Object[testCases.size()][1];
        int index = 0;
        for (String testCase : testCases.keySet()) {
            rows[index++][0] = testCase;
        }
        return rows;
    }

    @Test(dataProvider = "keywordTestCases", groups = {"keyword-driven", "recorded"},
        description = "Runs one test case from the recorded keyword script")
    public void runKeywordTestCase(String testCase) {
        runKeywordTestCase(objectRepository(), scriptPath(), testCase);
    }
}
