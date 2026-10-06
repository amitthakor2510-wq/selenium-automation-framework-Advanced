package com.automation.core.keyword;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.KeywordExecutionException;
import com.automation.core.utils.SensitiveData;

/**
 * One row of a keyword-driven test script.
 *
 * Expected columns in the source file (Excel/CSV/JSON/YAML):
 *   testCase    - groups steps into a scenario; KeywordReader groups by this
 *   stepNo      - execution order within a testCase (numeric)
 *   keyword     - one of the Keyword enum values (case-insensitive)
 *   locatorKey  - key into the ObjectRepository, e.g. "saucedemo.username" (optional for some keywords)
 *   testData    - input value for the step (text to type, seconds to wait, key to press...)
 *   expected    - expected value for VERIFY_* keywords
 *
 * testData and expected may contain ${env:NAME} / ${sys:NAME} / ${config:key} / ${i18n:key} placeholders
 * (see {@link PlaceholderResolver}) so credentials stay out of committed scripts. They are
 * expanded lazily, when the step actually runs, and only the raw placeholder text is ever
 * logged - a resolved secret never reaches toString(), the console or the reports.
 *   description - free-text, shown in logs/reports only
 */
public class KeywordStep {

    private final String testCase;
    private final int stepNo;
    private final Keyword keyword;
    private final String locatorKey;
    private final String testData;
    private final String expected;
    private final String description;

    public KeywordStep(DataRow row) {
        this.testCase    = row.getRequired("testCase");
        this.stepNo       = parseStepNo(row);
        this.keyword       = Keyword.from(row.getRequired("keyword"));
        this.locatorKey     = row.get("locatorKey");
        this.testData        = row.get("testData");
        this.expected         = row.get("expected");
        this.description       = row.get("description");
    }

    private static int parseStepNo(DataRow row) {
        String raw = row.getRequired("stepNo");
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new KeywordExecutionException("[KeywordStep] stepNo must be numeric, got: '" + raw
                + "' (row " + row.getRowIndex() + ")");
        }
    }

    public String getTestCase() { return testCase; }
    public int getStepNo() { return stepNo; }
    public Keyword getKeyword() { return keyword; }
    public String getLocatorKey() { return locatorKey; }
    /** testData with any ${env:...}/${sys:...}/${config:...}/${i18n:...} placeholders expanded. */
    public String getTestData() { return PlaceholderResolver.resolve(testData); }
    /** expected with any placeholders expanded. */
    public String getExpected() { return PlaceholderResolver.resolve(expected); }
    /** testData exactly as written in the script (placeholders NOT expanded) - safe to log. */
    public String getRawTestData() { return testData; }
    /** expected exactly as written in the script (placeholders NOT expanded) - safe to log. */
    public String getRawExpected() { return expected; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return "[" + testCase + " #" + stepNo + "] " + keyword
            + (locatorKey.isEmpty() ? "" : " -> " + locatorKey)
            + (testData.isEmpty() ? "" : " (\"" + SensitiveData.maskIfSensitive(locatorKey, testData) + "\")")
            + (description.isEmpty() ? "" : " // " + description);
    }
}
