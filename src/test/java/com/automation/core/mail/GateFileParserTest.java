package com.automation.core.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateFileParserTest {

    @Test
    void aggregatesEveryLegAndListsProblemLegs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a-chrome.txt"), "label=a-chrome status=ok total=10 passed=9 failed=1 skipped=0\n");
        Files.writeString(dir.resolve("b-chrome.txt"), "label=b-chrome status=ok total=5 passed=4 failed=0 skipped=1\n");
        Files.writeString(dir.resolve("c-chrome.txt"), "label=c-chrome status=missing\n");
        Files.writeString(dir.resolve("d.txt"), "label=d-empty status=ok total=0 passed=0 failed=0 skipped=0\n");
        Files.writeString(dir.resolve("ignored.json"), "{}");

        RunSummary s = GateFileParser.parseDirectory(dir, "x", "y");
        assertEquals(13, s.passed());
        assertEquals(1, s.failed());
        assertEquals(1, s.skipped());
        assertEquals(15, s.total());
        assertEquals("4 test legs", s.suite());
        assertEquals(3, s.failures().size());
        assertTrue(s.failures().stream().anyMatch(f -> f.name().equals("a-chrome")
            && f.message().contains("1 test(s) failed")));
        assertTrue(s.failures().stream().anyMatch(f -> f.name().equals("c-chrome")));
        assertTrue(s.failures().stream().anyMatch(f -> f.name().equals("d-empty")
            && f.message().contains("0 tests")));
    }

    @Test
    void noFilesMeansNull(@TempDir Path dir) throws Exception {
        assertNull(GateFileParser.parseDirectory(dir, "", ""));
        assertNull(GateFileParser.parseDirectory(dir.resolve("absent"), "", ""));
    }

    @Test
    void garbageNumbersDoNotCrash(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("x.txt"), "label=x status=ok passed=abc failed= skipped=2");
        RunSummary s = GateFileParser.parseDirectory(dir, "", "");
        assertEquals(2, s.skipped());
        assertEquals(0, s.passed());
    }
}
