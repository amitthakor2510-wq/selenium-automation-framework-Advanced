package com.automation.core.keyword;

import com.automation.core.exceptions.DataFileException;
import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads real keyword scripts (CSV on disk) through {@link KeywordReader}: grouping by test case,
 * ordering by step number, and the errors a typo in a script file produces. No browser involved.
 */
class KeywordReaderTest {

    private static final String HEADER = "testCase,stepNo,keyword,locatorKey,testData,expected,description\n";

    @Test
    void groupsStepsByTestCaseInTheOrderTheCasesFirstAppear(@TempDir Path tempDir)
        throws IOException {
        String path = script(tempDir,
            "TC_B,1,NAVIGATE,,/home,,open\n"
                + "TC_A,1,CLICK,btn.a,,,click\n"
                + "TC_B,2,CLICK,btn.b,,,next\n");

        Map<String, List<KeywordStep>> all = KeywordReader.readAll(path);

        assertEquals(List.of("TC_B", "TC_A"), new ArrayList<>(all.keySet()));
        assertEquals(2, all.get("TC_B").size());
        assertEquals(1, all.get("TC_A").size());
    }

    @Test
    void sortsStepsByNumericStepNumberNotAlphabetically(@TempDir Path tempDir)
        throws IOException {
        String path = script(tempDir,
            "TC,10,CLICK,btn.ok,,,third\n"
                + "TC,2,TYPE,field.name,john,,second\n"
                + "TC,1,NAVIGATE,,/home,,first\n");

        List<KeywordStep> steps = KeywordReader.readTestCase(path, "TC");

        assertEquals(List.of(1, 2, 10), steps.stream().map(KeywordStep::getStepNo).toList());
        assertEquals(List.of("first", "second", "third"),
            steps.stream().map(KeywordStep::getDescription).toList());
    }

    @Test
    void readTestCaseReturnsOnlyTheRequestedCase(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir,
            "TC_A,1,CLICK,btn.a,,,a1\n"
                + "TC_B,1,CLICK,btn.b,,,b1\n"
                + "TC_B,2,CLICK,btn.c,,,b2\n");

        List<KeywordStep> steps = KeywordReader.readTestCase(path, "TC_B");

        assertEquals(2, steps.size());
        assertTrue(steps.stream().allMatch(s -> s.getTestCase().equals("TC_B")));
    }

    @Test
    void everyStepRemembersTheScriptItCameFrom(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "TC,1,CLICK,btn.a,,,only\n");

        KeywordStep step = KeywordReader.readTestCase(path, "TC").get(0);

        assertEquals(path, step.getSourceFile());
    }

    @Test
    void unknownTestCaseNamesTheCaseAndTheFile(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "TC,1,CLICK,btn.a,,,only\n");

        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> KeywordReader.readTestCase(path, "NOPE"));

        assertTrue(ex.getMessage().contains("No rows found for testCase='NOPE'"), ex.getMessage());
        assertTrue(ex.getMessage().contains(path), ex.getMessage());
    }

    @Test
    void testCaseNamesAreCaseSensitive(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "TC_Login,1,CLICK,btn.a,,,only\n");

        assertThrows(KeywordExecutionException.class,
            () -> KeywordReader.readTestCase(path, "tc_login"));
    }

    @Test
    void typoInTheKeywordColumnFailsAtReadTimeNotMidRun(@TempDir Path tempDir)
        throws IOException {
        String path = script(tempDir, "TC,1,NAVIGATE,,/home,,ok\nTC,2,CLIK,btn.a,,,typo\n");

        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> KeywordReader.readAll(path));

        assertTrue(ex.getMessage().contains("Unknown keyword: 'CLIK'"), ex.getMessage());
    }

    @Test
    void nonNumericStepNumberFailsAtReadTime(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "TC,one,CLICK,btn.a,,,bad\n");

        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> KeywordReader.readAll(path));

        assertTrue(ex.getMessage().contains("stepNo must be numeric"), ex.getMessage());
        assertTrue(ex.getMessage().contains("row 1"), ex.getMessage());
    }

    @Test
    void rowWithoutAKeywordFailsAtReadTime(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "TC,1,,btn.a,,,no keyword\n");

        DataFileException ex = assertThrows(DataFileException.class,
            () -> KeywordReader.readAll(path));

        assertTrue(ex.getMessage().contains("'keyword'"), ex.getMessage());
    }

    @Test
    void emptyScriptHasNoTestCases(@TempDir Path tempDir) throws IOException {
        String path = script(tempDir, "");

        assertTrue(KeywordReader.readAll(path).isEmpty());
    }

    @Test
    void missingScriptFileIsReportedAsADataFileProblem() {
        assertThrows(DataFileException.class,
            () -> KeywordReader.readAll("does/not/exist/keywords.csv"));
    }

    private static String script(Path tempDir, String rows) throws IOException {
        Path file = tempDir.resolve("keywords.csv");
        Files.writeString(file, HEADER + rows, StandardCharsets.UTF_8);
        return file.toString();
    }
}
