package com.automation.core.report.pdf;

import com.automation.core.report.pdf.PdfReportData.Status;
import com.automation.core.report.pdf.PdfReportData.TestEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportWriterTest {

    private static TestEntry pass(String name) {
        return new TestEntry(name, "LoginTest", Status.PASSED, 1200, "", "", null);
    }

    private static TestEntry fail(String name, byte[] screenshot) {
        return new TestEntry(name, "LoginTest", Status.FAILED, 3400,
            "org.openqa.selenium.NoSuchElementException: no such element #submit",
            "at com.automation.sites.saucedemo.pages.LoginPage.submit(LoginPage.java:42)", screenshot);
    }

    private static PdfReportData data(List<TestEntry> tests) {
        return new PdfReportData("Test Execution Report", "saucedemo", "chrome", "SauceDemo Smoke",
            Instant.parse("2026-10-21T11:00:00Z"), 65_000, Map.of("Site", "saucedemo"), tests);
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 90, 200, 200));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String latin1(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    private static int pageCount(byte[] pdf) {
        Matcher m = Pattern.compile("/Type /Page /Parent").matcher(latin1(pdf));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Every xref entry must point at "N 0 obj" - the thing PDF readers check before they trust a file. */
    private static void assertStructurallyValid(byte[] pdf) {
        String text = latin1(pdf);
        assertTrue(text.startsWith("%PDF-1.4"), "header");
        assertTrue(text.stripTrailing().endsWith("%%EOF"), "trailer");
        Matcher start = Pattern.compile("startxref\n(\\d+)\n").matcher(text);
        assertTrue(start.find(), "startxref");
        int xref = Integer.parseInt(start.group(1));
        assertTrue(text.startsWith("xref\n", xref), "startxref must point at the xref table");
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(text.substring(xref));
        int id = 0;
        while (entries.find()) {
            id++;
            int offset = Integer.parseInt(entries.group(1));
            assertTrue(text.startsWith(id + " 0 obj\n", offset), "object " + id + " at " + offset);
        }
        assertTrue(id >= 6, "catalog, pages, info and fonts at least");
    }

    @Test
    void rendersAStructurallyValidPdf() throws Exception {
        byte[] pdf = PdfReportWriter.render(data(List.of(pass("ok"), fail("bad", null))),
            PdfReportSettings.defaults());
        assertStructurallyValid(pdf);
        assertTrue(pageCount(pdf) <= 2, "a two-test report is one or two pages");
        assertTrue(latin1(pdf).contains("/Outlines"), "bookmarks for the sections");
    }

    @Test
    void manyTestsFlowOntoMorePages() {
        List<TestEntry> tests = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            tests.add(pass("test" + i));
        }
        byte[] pdf = PdfReportWriter.render(data(tests), PdfReportSettings.defaults());
        assertStructurallyValid(pdf);
        assertTrue(pageCount(pdf) > 3, "300 rows cannot fit on one page, got " + pageCount(pdf));
    }

    @Test
    void failureScreenshotIsEmbeddedAsJpeg() throws Exception {
        byte[] pdf = PdfReportWriter.render(data(List.of(fail("bad", png(1280, 720)))),
            PdfReportSettings.defaults());
        assertStructurallyValid(pdf);
        assertTrue(latin1(pdf).contains("/DCTDecode"), "JPEG image object");
    }

    @Test
    void screenshotsCanBeSwitchedOffOrCapped() throws Exception {
        PdfReportData d = data(List.of(fail("a", png(400, 300)), fail("b", png(400, 300))));
        PdfReportSettings off = PdfReportSettings.of(Map.of("report.pdf.screenshots", "false")::get, Map.of());
        assertFalse(latin1(PdfReportWriter.render(d, off)).contains("/DCTDecode"));

        PdfReportSettings one = PdfReportSettings.of(Map.of("report.pdf.screenshots.max", "1")::get, Map.of());
        String capped = latin1(PdfReportWriter.render(d, one));
        assertEquals(1, capped.split("/DCTDecode", -1).length - 1, "only one screenshot embedded");
    }

    @Test
    void unreadableScreenshotIsSkippedNotFatal() {
        byte[] pdf = PdfReportWriter.render(data(List.of(fail("bad", new byte[] {1, 2, 3, 4}))),
            PdfReportSettings.defaults());
        assertStructurallyValid(pdf);
        assertFalse(latin1(pdf).contains("/DCTDecode"));
    }

    @Test
    void awkwardTextNeverBreaksTheFile() {
        String nasty = "caf\u00e9 \u201cquoted\u201d \u2014 \u0939\u093f\u0928\u094d\u0926\u0940 \uD83D\uDE00 (unbalanced \\ ) ( "
            + "x".repeat(5000);
        TestEntry weird = new TestEntry("t\u00e9st(\")" + "n".repeat(300), "Cls\\", Status.FAILED, 1, nasty,
            "at a.B.c(B.java:1)\n\n   \tat d.E.f(E.java:2)", null);
        byte[] pdf = PdfReportWriter.render(data(List.of(weird, pass("p"))), PdfReportSettings.defaults());
        assertStructurallyValid(pdf);
    }

    @Test
    void emptyRunIsReportedAsNoTests() {
        PdfReportData d = new PdfReportData(null, null, null, null, null, 0, null, List.of());
        assertEquals("NO TESTS", d.status());
        assertStructurallyValid(PdfReportWriter.render(d, PdfReportSettings.defaults()));
    }

    @Test
    void includePassedFalseKeepsOnlyProblemsInTheTable() {
        List<TestEntry> tests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tests.add(pass("test" + i));
        }
        PdfReportSettings compact = PdfReportSettings.of(Map.of("report.pdf.include.passed", "false")::get, Map.of());
        int full = pageCount(PdfReportWriter.render(data(tests), PdfReportSettings.defaults()));
        int small = pageCount(PdfReportWriter.render(data(tests), compact));
        assertTrue(small < full, "compact " + small + " pages vs full " + full);
    }

    @Test
    void writeCreatesFoldersAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a/b/report.pdf");
        PdfReportWriter.write(data(List.of(pass("ok"))), PdfReportSettings.defaults(), target);
        assertTrue(Files.size(target) > 500);
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertEquals(List.of("report.pdf"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void fileNameIsAFilesystemSafeSlug() {
        assertEquals("saucedemo-chrome-sauce-demo-smoke-report.pdf",
            PdfReportWriter.fileName(new PdfReportData("t", "saucedemo", "chrome", "Sauce Demo / Smoke!", null, 0,
                null, List.of())));
        assertEquals("test-report.pdf",
            PdfReportWriter.fileName(new PdfReportData("t", "", "", "", null, 0, null, List.of())));
    }

    @Test
    void summaryNumbers() {
        PdfReportData d = data(List.of(pass("a"), pass("b"), fail("c", null),
            new TestEntry("d", "X", Status.SKIPPED, 0, "why", "", null)));
        assertEquals(4, d.total());
        assertEquals(2, d.passed());
        assertEquals(1, d.failed());
        assertEquals(1, d.skipped());
        assertEquals("FAILED", d.status());
        assertEquals(50.0, d.passRate(), 0.0001);
    }

    @Test
    void durationsAreHumanReadable() {
        assertEquals("-", PdfReportWriter.duration(0));
        assertEquals("850 ms", PdfReportWriter.duration(850));
        assertEquals("1.2 s", PdfReportWriter.duration(1200));
        assertEquals("3m 12s", PdfReportWriter.duration(192_000));
        assertEquals("1h 02m", PdfReportWriter.duration(3_720_000));
    }

    @Test
    void fitAndWrapKeepTextInsideTheColumn() {
        PdfDocument.Font f = PdfDocument.Font.REGULAR;
        String fitted = PdfReportWriter.fit(f, 9, "a very long test name that cannot possibly fit", 80);
        assertTrue(PdfDocument.width(f, 9, fitted) <= 80, fitted);
        assertTrue(fitted.endsWith("\u2026"), fitted);
        assertEquals("short", PdfReportWriter.fit(f, 9, "short", 80));

        for (String line : PdfReportWriter.wrap(PdfDocument.Font.MONO, 8, "x".repeat(400) + " tail words here", 200)) {
            assertTrue(PdfDocument.width(PdfDocument.Font.MONO, 8, line) <= 200.001, line);
        }
    }
}
