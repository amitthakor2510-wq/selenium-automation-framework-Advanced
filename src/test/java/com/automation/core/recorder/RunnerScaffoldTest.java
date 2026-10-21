package com.automation.core.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the one-off runner class and suite file generated per site. */
class RunnerScaffoldTest {

    @TempDir
    Path root;

    @Test
    void classNamesAreStableForAnySiteSpelling() {
        assertEquals("RecordedSaucedemoKeywordTest", RunnerScaffold.className("saucedemo"));
        assertEquals("RecordedSahmatKeywordTest", RunnerScaffold.className("SAHMAT"));
    }

    @Test
    void createsTheClassAndSuiteOnceAndNeverOverwrites() throws IOException {
        Files.createDirectories(root.resolve("src/test/java/com/automation/sites/sahmat/tests"));
        Files.createDirectories(root.resolve("testng-suites"));

        List<Path> first = RunnerScaffold.ensure(root, "SAHMAT", "objectrepository/SAHMAT.properties",
            "src/test/resources/testdata/keyword/SAHMAT_recorded_keywords.csv");
        assertEquals(2, first.size());
        Path source = root.resolve("src/test/java/com/automation/sites/sahmat/tests/RecordedSahmatKeywordTest.java");
        String text = Files.readString(source);
        assertTrue(text.contains("package com.automation.sites.sahmat.tests;"));
        assertTrue(text.contains("extends KeywordScriptSuiteBase"));
        assertTrue(text.contains("\"objectrepository/SAHMAT.properties\""));
        assertTrue(text.contains("\"src/test/resources/testdata/keyword/SAHMAT_recorded_keywords.csv\""));
        String suite = Files.readString(root.resolve("testng-suites/SAHMAT-recorded.xml"));
        assertTrue(suite.contains("com.automation.sites.sahmat.tests.RecordedSahmatKeywordTest"));

        Files.writeString(source, text + "// hand edit\n");
        assertTrue(RunnerScaffold.ensure(root, "SAHMAT", "x", "y").isEmpty());
        assertTrue(Files.readString(source).endsWith("// hand edit\n"), "an existing class must never be rewritten");
    }

    @Test
    void aSiteWithoutATestPackageGetsNothing() throws IOException {
        assertTrue(RunnerScaffold.ensure(root, "newsite", "a", "b").isEmpty());
    }
}
