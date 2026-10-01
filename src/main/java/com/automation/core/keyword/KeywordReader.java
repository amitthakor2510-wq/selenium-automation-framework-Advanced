package com.automation.core.keyword;

import com.automation.core.data.DataProvider;
import com.automation.core.data.DataRow;
import com.automation.core.exceptions.KeywordExecutionException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads a keyword-driven test script (any format DataProvider supports:
 * Excel/CSV/JSON/YAML/ZIP) and groups its rows into ordered KeywordStep
 * lists per testCase.
 *
 * Usage:
 *   List<KeywordStep> steps = KeywordReader.readTestCase(
 *       "src/test/resources/testdata/keyword/saucedemo_login_keywords.csv",
 *       "TC01_ValidLogin");
 */
public final class KeywordReader {

    private KeywordReader() {}

    /** All test cases in the file, keyed by testCase name, steps sorted by stepNo. */
    public static Map<String, List<KeywordStep>> readAll(String filePath) {
        // Keyword step rows are a fixed script, not filterable DDT data rows, so this
        // must use the unfiltered DataProvider.readAll() — not DataProvider.read().
        // read() applies the execute/tags DDT filters, which are designed for
        // data-driven test *data* rows. Keyword files normally have no
        // execute/tags columns, so filterExecuteOnly() is a no-op on them - but
        // the tags filter is not: if a caller sets -Ddata.tags=<anything> (a
        // documented, supported flag for regular DDT tests) while also running
        // keyword-driven tests, rowMatchesTags() drops every row here because
        // keyword files don't have a "tags" column, and readTestCase() then
        // throws "No rows found for testCase=...", unrelated to the actual
        // script content.
        List<DataRow> rows = DataProvider.readAll(filePath);

        Map<String, List<KeywordStep>> byTestCase = new LinkedHashMap<>();
        for (DataRow row : rows) {
            KeywordStep step = new KeywordStep(row);
            byTestCase.computeIfAbsent(step.getTestCase(), k -> new ArrayList<>()).add(step);
        }

        byTestCase.replaceAll((testCase, steps) -> steps.stream()
            .sorted(Comparator.comparingInt(KeywordStep::getStepNo))
            .collect(Collectors.toList()));

        return byTestCase;
    }

    /** Just the steps for one testCase, in stepNo order. */
    public static List<KeywordStep> readTestCase(String filePath, String testCase) {
        List<KeywordStep> steps = readAll(filePath).get(testCase);
        if (steps == null) {
            throw new KeywordExecutionException("[KeywordReader] No rows found for testCase='"
                + testCase + "' in " + filePath);
        }
        return steps;
    }
}
