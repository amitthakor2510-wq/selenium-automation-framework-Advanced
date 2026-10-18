package com.automation.core.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip tests for the files {@link ScriptWriter} writes, read back the way the framework reads them. */
class ScriptWriterTest {

    @TempDir
    Path dir;

    private static RecordedStep step(String keyword, String key, String data, String expected, String description) {
        return new RecordedStep(keyword, key, data, expected, description, 0);
    }

    @Test
    void writesAHeaderAndNumberedRowsThatSurviveCommasAndQuotes() throws IOException {
        Path csv = dir.resolve("s.csv");
        ScriptWriter.writeScript(csv, "TC_A", List.of(
            step("NAVIGATE", "", "/x", "", "Open"),
            step("TYPE", "lab.a.q", "he said \"hi\", twice", "", "Type, with comma")), false);

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(ScriptWriter.CSV_HEADER, lines.get(0));
        assertEquals("TC_A,1,NAVIGATE,,/x,,Open", lines.get(1));
        assertEquals("TC_A,2,TYPE,lab.a.q,\"he said \"\"hi\"\", twice\",,\"Type, with comma\"", lines.get(2));
        assertEquals(Set.of("TC_A"), ScriptWriter.existingTestCases(csv));
    }

    @Test
    void addsASecondTestCaseWithoutTouchingTheFirstAndRefusesADuplicate() throws IOException {
        Path csv = dir.resolve("s.csv");
        ScriptWriter.writeScript(csv, "TC_A", List.of(step("NAVIGATE", "", "/a", "", "")), false);
        String before = Files.readString(csv);
        ScriptWriter.writeScript(csv, "TC_B", List.of(step("NAVIGATE", "", "/b", "", "")), false);

        assertTrue(Files.readString(csv).startsWith(before));
        assertEquals(Set.of("TC_A", "TC_B"), ScriptWriter.existingTestCases(csv));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ScriptWriter.writeScript(csv, "TC_A", List.of(step("NAVIGATE", "", "/z", "", "")), false));
        assertTrue(e.getMessage().contains("--overwrite"));
    }

    @Test
    void overwriteReplacesOnlyThatTestCase() throws IOException {
        Path csv = dir.resolve("s.csv");
        ScriptWriter.writeScript(csv, "TC_A", List.of(step("NAVIGATE", "", "/a", "", "")), false);
        ScriptWriter.writeScript(csv, "TC_B", List.of(step("NAVIGATE", "", "/b", "", "")), false);
        ScriptWriter.writeScript(csv, "TC_A", List.of(step("NAVIGATE", "", "/a2", "", ""), step("REFRESH", "", "", "", "")), true);

        String text = Files.readString(csv);
        assertTrue(text.contains("TC_B,1,NAVIGATE,,/b"));
        assertTrue(text.contains("TC_A,1,NAVIGATE,,/a2"));
        assertTrue(text.contains("TC_A,2,REFRESH"));
        assertFalse(text.contains("/a,"), "the old TC_A row must be gone: " + text);
        assertEquals(1, text.split("testCase,stepNo", -1).length - 1, "exactly one header line");
    }

    @Test
    void repositoryEntriesAreAppendedOnceAndExistingContentIsKept() throws IOException {
        Path props = dir.resolve("site.properties");
        Files.writeString(props, "site.old = id:old", StandardCharsets.ISO_8859_1); // no trailing newline on purpose
        LocatorSpec fresh = new LocatorSpec("site.a.user",
            List.of(new Candidate("id", "user", true), new Candidate("name", "u", true)), null, "http://x/a", false);
        LocatorSpec known = new LocatorSpec("site.a.old", List.of(new Candidate("id", "old", true)), null, "http://x/a", true);

        assertEquals(2, ScriptWriter.appendRepository(props, "TC_A", List.of(fresh, known)));
        assertEquals(0, ScriptWriter.appendRepository(props, "TC_A", List.of(known)), "keys already present add nothing");

        Map<String, String> read = ScriptWriter.readRepository(props);
        assertEquals("id:old", read.get("site.old"));
        assertEquals("id:user", read.get("site.a.user"));
        assertEquals("name:u", read.get("site.a.user.fallback.1"));
        assertEquals(3, read.size());
    }

    @Test
    void nonAsciiAndBackslashesSurviveTheIso88591PropertiesFormat() throws IOException {
        Path props = dir.resolve("hi.properties");
        String xpath = "xpath://a[normalize-space()='\u0928\u092e\u0938\u094d\u0924\u0947 \u2013 caf\u00e9'] \\ end";
        LocatorSpec spec = new LocatorSpec("site.a.link", List.of(new Candidate("xpath",
            xpath.substring("xpath:".length()), true)), null, "http://x", false);
        ScriptWriter.appendRepository(props, "TC", List.of(spec));

        assertTrue(Files.readString(props, StandardCharsets.ISO_8859_1).chars().allMatch(c -> c < 128),
            "the file must be pure ASCII so any decoder reads it the same way");
        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(props)) {
            loaded.load(in);
        }
        assertEquals(xpath, loaded.getProperty("site.a.link"));
    }

    @Test
    void readingMissingFilesGivesEmptyResults() throws IOException {
        assertTrue(ScriptWriter.readRepository(dir.resolve("none.properties")).isEmpty());
        assertTrue(ScriptWriter.existingTestCases(dir.resolve("none.csv")).isEmpty());
    }
}
