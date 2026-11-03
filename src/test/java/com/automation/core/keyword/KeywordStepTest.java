package com.automation.core.keyword;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One row of a keyword script becoming a {@link KeywordStep}: what is required, what is
 * optional, and how mistakes in the script file are reported. No browser involved.
 */
class KeywordStepTest {

    @Test
    void readsEveryColumnOfARow() {
        Map<String, String> cells = minimal("TC01", "3", "click");
        cells.put("locatorKey", "shop.cart");
        cells.put("testData", "typed text");
        cells.put("expected", "some result");
        cells.put("description", "open the cart");

        KeywordStep step = new KeywordStep(new DataRow(cells, 1));

        assertEquals("TC01", step.getTestCase());
        assertEquals(3, step.getStepNo());
        assertEquals(Keyword.CLICK, step.getKeyword());
        assertEquals("shop.cart", step.getLocatorKey());
        assertEquals("typed text", step.getRawTestData());
        assertEquals("some result", step.getRawExpected());
        assertEquals("open the cart", step.getDescription());
    }

    @Test
    void optionalColumnsDefaultToEmptyStrings() {
        KeywordStep step = new KeywordStep(new DataRow(minimal("TC01", "1", "navigate"), 1));

        assertEquals("", step.getLocatorKey());
        assertEquals("", step.getRawTestData());
        assertEquals("", step.getRawExpected());
        assertEquals("", step.getDescription());
    }

    @Test
    void stepNumberMayHaveSurroundingWhitespace() {
        KeywordStep step = new KeywordStep(new DataRow(minimal("TC01", "  7 ", "click"), 1));

        assertEquals(7, step.getStepNo());
    }

    @Test
    void nonNumericStepNumberIsReportedWithItsValueAndRow() {
        DataRow row = new DataRow(minimal("TC01", "abc", "click"), 5);

        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> new KeywordStep(row));

        assertTrue(ex.getMessage().contains("stepNo must be numeric"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'abc'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("row 5"), ex.getMessage());
    }

    @Test
    void decimalStepNumberIsRejected() {
        DataRow row = new DataRow(minimal("TC01", "1.5", "click"), 1);

        assertThrows(KeywordExecutionException.class, () -> new KeywordStep(row));
    }

    @Test
    void missingTestCaseIsRejected() {
        DataRow row = new DataRow(minimal("", "1", "click"), 2);

        DataFileException ex = assertThrows(DataFileException.class, () -> new KeywordStep(row));

        assertTrue(ex.getMessage().contains("'testCase'"), ex.getMessage());
    }

    @Test
    void missingKeywordCellIsRejected() {
        DataRow row = new DataRow(minimal("TC01", "1", ""), 2);

        DataFileException ex = assertThrows(DataFileException.class, () -> new KeywordStep(row));

        assertTrue(ex.getMessage().contains("'keyword'"), ex.getMessage());
    }

    @Test
    void unknownKeywordIsRejectedWithTheTypoInTheMessage() {
        DataRow row = new DataRow(minimal("TC01", "1", "FLY"), 1);

        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> new KeywordStep(row));

        assertTrue(ex.getMessage().contains("Unknown keyword: 'FLY'"), ex.getMessage());
    }

    @Test
    void sourceFileIsEmptyUntilTheReaderSetsIt() {
        KeywordStep step = new KeywordStep(new DataRow(minimal("TC01", "1", "click"), 1));
        assertNull(step.getSourceFile());

        step.setSourceFile("scripts/login.csv");

        assertEquals("scripts/login.csv", step.getSourceFile());
    }

    @Test
    void toStringOfABareStepShowsOnlyTestCaseNumberAndKeyword() {
        KeywordStep step = new KeywordStep(new DataRow(minimal("TC01", "1", "navigate"), 1));

        assertEquals("[TC01 #1] NAVIGATE", step.toString());
    }

    @Test
    void toStringShowsLocatorDataAndDescription() {
        Map<String, String> cells = minimal("TC01", "2", "type");
        cells.put("locatorKey", "login.username");
        cells.put("testData", "john");
        cells.put("description", "enter name");

        KeywordStep step = new KeywordStep(new DataRow(cells, 1));

        assertEquals("[TC01 #2] TYPE -> login.username (\"john\") // enter name",
            step.toString());
    }

    @Test
    void toStringMasksTheValueTypedIntoASecretField() {
        Map<String, String> cells = minimal("TC01", "2", "type");
        cells.put("locatorKey", "login.password");
        cells.put("testData", "hunter2");

        String text = new KeywordStep(new DataRow(cells, 1)).toString();

        assertFalse(text.contains("hunter2"), text);
        assertTrue(text.contains("********"), text);
    }

    @Test
    void toStringNeverContainsAResolvedPlaceholder() {
        Map<String, String> cells = minimal("TC01", "2", "type");
        cells.put("locatorKey", "login.username");
        cells.put("testData", "${env:SOME_SECRET_THAT_IS_NOT_SET}");

        String text = new KeywordStep(new DataRow(cells, 1)).toString();

        // Only the raw placeholder text is ever logged; resolution happens when the step runs.
        assertTrue(text.contains("${env:SOME_SECRET_THAT_IS_NOT_SET}"), text);
    }

    private static Map<String, String> minimal(String testCase, String stepNo, String keyword) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("testCase", testCase);
        cells.put("stepNo", stepNo);
        cells.put("keyword", keyword);
        return cells;
    }
}
