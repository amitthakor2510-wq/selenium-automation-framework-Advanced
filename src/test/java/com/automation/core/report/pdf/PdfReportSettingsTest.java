package com.automation.core.report.pdf;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportSettingsTest {

    @Test
    void offByDefaultWithSensibleDefaults() {
        PdfReportSettings s = PdfReportSettings.defaults();
        assertFalse(s.enabled());
        assertEquals(Path.of("target/pdf-reports"), s.outputDir());
        assertEquals("Test Execution Report", s.title());
        assertTrue(s.screenshots());
        assertEquals(25, s.maxScreenshots());
        assertEquals(10, s.stackTraceLines());
        assertTrue(s.includePassed());
        assertEquals(50, s.maxFailureDetails());
    }

    @Test
    void propertiesOverrideDefaults() {
        Map<String, String> p = new HashMap<>();
        p.put("report.pdf.enabled", "true");
        p.put("report.pdf.output.dir", "out/pdf");
        p.put("report.pdf.stacktrace.lines", "3");
        p.put("report.pdf.screenshots", "false");
        PdfReportSettings s = PdfReportSettings.of(p::get, Map.of());
        assertTrue(s.enabled());
        assertEquals(Path.of("out/pdf"), s.outputDir());
        assertEquals(3, s.stackTraceLines());
        assertFalse(s.screenshots());
    }

    @Test
    void environmentVariableFillsABlankKey() {
        Map<String, String> p = new HashMap<>();
        p.put("report.pdf.enabled", "");
        PdfReportSettings s = PdfReportSettings.of(p::get, Map.of("REPORT_PDF_ENABLED", "true"));
        assertTrue(s.enabled(), "the shipped global.properties leaves the key blank so this works");
    }

    @Test
    void aPropertyBeatsTheEnvironment() {
        PdfReportSettings s = PdfReportSettings.of(Map.of("report.pdf.enabled", "false")::get,
            Map.of("REPORT_PDF_ENABLED", "true"));
        assertFalse(s.enabled());
    }

    @Test
    void junkNumbersFallBackAndNegativesAreClamped() {
        Map<String, String> p = new HashMap<>();
        p.put("report.pdf.screenshots.max", "lots");
        p.put("report.pdf.stacktrace.lines", "-4");
        p.put("report.pdf.screenshots.maxWidthPx", "10");
        PdfReportSettings s = PdfReportSettings.of(p::get, Map.of());
        assertEquals(25, s.maxScreenshots());
        assertEquals(0, s.stackTraceLines());
        assertEquals(200, s.screenshotMaxWidthPx());
    }
}
