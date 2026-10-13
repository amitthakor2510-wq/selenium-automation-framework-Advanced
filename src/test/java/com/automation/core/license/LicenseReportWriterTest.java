package com.automation.core.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseReportWriterTest {

    private static LicenseScanResult sample() {
        LicensePolicy policy = LicensePolicy.defaults();
        List<DependencyLicense> rows = List.of(
            new DependencyLicense("org.bad", "gpl-lib", "1", List.of("GPL v3"), List.of("GPL-3.0"),
                LicenseStatus.DENIED, "pom", ""),
            new DependencyLicense("org.x", "<script>alert(1)</script>", "1", List.of("Evil & \"Co\""),
                List.of("Evil & \"Co\""), LicenseStatus.REVIEW, "pom", "note <b>bold</b>"),
            new DependencyLicense("org.ok", "fine", "2", List.of("MIT"), List.of("MIT"),
                LicenseStatus.ALLOWED, "pom", ""));
        return new LicenseScanResult(rows, 3, policy);
    }

    @Test
    void writesAllFourFiles(@TempDir Path dir) throws Exception {
        LicenseReportWriter.write(sample(), dir.resolve("out"));
        for (String f : List.of(LicenseReportWriter.HTML, LicenseReportWriter.JSON, LicenseReportWriter.TEXT,
            LicenseReportWriter.SUMMARY)) {
            assertTrue(Files.isRegularFile(dir.resolve("out").resolve(f)), f);
        }
    }

    @Test
    void htmlEscapesEveryDynamicValue(@TempDir Path dir) throws Exception {
        String html = LicenseReportWriter.html(sample());
        assertFalse(html.contains("<script>alert"), "raw script tag must never reach the report");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Evil &amp; &quot;Co&quot;"));
        assertTrue(html.contains("note &lt;b&gt;bold&lt;/b&gt;"));
    }

    @Test
    void jsonIsValidAndComplete(@TempDir Path dir) throws Exception {
        LicenseReportWriter.write(sample(), dir);
        JsonNode root = new ObjectMapper().readTree(dir.resolve(LicenseReportWriter.JSON).toFile());
        assertEquals(3, root.get("dependencies").size());
        assertEquals(1, root.get("counts").get("DENIED").asInt());
        assertEquals(3, root.get("ignored").asInt());
        assertEquals(1, root.get("failing").asInt());
        assertEquals("GPL-3.0", root.get("dependencies").get(0).get("licenseIds").get(0).asText());
    }

    @Test
    void summaryRoundTrips(@TempDir Path dir) throws Exception {
        LicenseReportWriter.write(sample(), dir);
        LicenseReportWriter.Summary s = LicenseReportWriter.readSummary(dir).orElseThrow();
        assertEquals(new LicenseReportWriter.Summary(3, 1, 1, 1, 0, 1), s);
    }

    @Test
    void readSummaryIsEmptyWhenAbsentOrGarbage(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), LicenseReportWriter.readSummary(dir));
        Files.writeString(dir.resolve(LicenseReportWriter.SUMMARY), "total=abc\nallowed=\n");
        LicenseReportWriter.Summary s = LicenseReportWriter.readSummary(dir).orElseThrow();
        assertEquals(0, s.total());
    }

    @Test
    void textListsOnlyProblemRows() {
        String text = LicenseReportWriter.text(sample());
        assertTrue(text.contains("[DENIED] org.bad:gpl-lib:1"));
        assertTrue(text.contains("[REVIEW]"));
        assertFalse(text.contains("org.ok:fine"), "allowed rows are not listed in the console summary");
        assertTrue(text.contains("3 ignored by policy"));
    }
}
