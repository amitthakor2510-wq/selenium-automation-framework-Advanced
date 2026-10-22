package com.automation.core.report.pdf;

import com.automation.core.report.pdf.PdfDocument.Font;
import com.automation.core.report.pdf.PdfReportData.Status;
import com.automation.core.report.pdf.PdfReportData.TestEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link PdfReportData} as a PDF: a header with the run status, summary figures and a
 * pass-rate bar, the environment, a per-class breakdown, one detailed block per failure (message,
 * condensed stack trace, screenshot), the skipped tests and the full results table. Every page has a
 * footer with page numbers and the PDF has bookmarks for each section.
 *
 * <p>The writer never throws for unusual content: text outside the Latin set is replaced, an
 * unreadable screenshot is skipped, and very long names are shortened with an ellipsis.
 */
public final class PdfReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(PdfReportWriter.class);

    private static final float MARGIN = 40f;
    private static final float CONTENT_WIDTH = PdfDocument.PAGE_WIDTH - 2 * MARGIN;
    private static final float TOP = PdfDocument.PAGE_HEIGHT - 50f;
    private static final float BOTTOM = 62f;
    private static final float ROW_HEIGHT = 15f;
    private static final float MAX_SHOT_HEIGHT = 300f;

    private static final int NAVY = 0x1F2A44;
    private static final int TABLE_HEAD = 0x374151;
    private static final int TEXT = 0x111827;
    private static final int MUTED = 0x6B7280;
    private static final int LIGHT = 0xF3F4F6;
    private static final int BORDER = 0xD1D5DB;
    private static final int GREEN = 0x2E9E5B;
    private static final int RED = 0xD64545;
    private static final int RED_TINT = 0xFDECEC;
    private static final int AMBER = 0xE0A526;
    private static final int GREY_STATUS = 0x6B7280;
    private static final int WHITE = 0xFFFFFF;
    private static final int GREEN_TINT = 0xE8F5EE;
    private static final int AMBER_TINT = 0xFFF6E0;
    private static final int GREY_TINT = 0xEEF0F3;
    private static final int DEFAULT_COLOR = -1;

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private PdfReportWriter() {
    }

    /** Renders the report and writes it to {@code file} (parent folders are created). */
    public static Path write(PdfReportData data, PdfReportSettings settings, Path file) throws IOException {
        byte[] pdf = render(data, settings);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        // A sibling temp name (not createTempFile, which makes the file owner-only): the finished PDF
        // keeps the normal umask, so CI users, Docker volumes and mail jobs can all read it.
        Path tmp = file.toAbsolutePath().resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, pdf);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return file;
    }

    /** The PDF as bytes. */
    public static byte[] render(PdfReportData data, PdfReportSettings settings) {
        return new Renderer(data, settings).build();
    }

    /** A filesystem-safe name such as {@code demoqa-chrome-demoqa-regression-suite-report.pdf}. */
    public static String fileName(PdfReportData data) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {data.site(), data.browser(), data.suite()}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('-');
                }
                sb.append(part);
            }
        }
        String slug = sb.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 100) {
            slug = slug.substring(0, 100).replaceAll("-+$", "");
        }
        return (slug.isEmpty() ? "test" : slug) + "-report.pdf";
    }

    /** {@code 850 ms}, {@code 1.2 s}, {@code 3m 12s}, {@code 1h 02m}. */
    static String duration(long ms) {
        if (ms <= 0) {
            return "-";
        }
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        }
        long totalSeconds = ms / 1000;
        if (totalSeconds < 3600) {
            return String.format(Locale.ROOT, "%dm %02ds", totalSeconds / 60, totalSeconds % 60);
        }
        return String.format(Locale.ROOT, "%dh %02dm", totalSeconds / 3600, (totalSeconds % 3600) / 60);
    }

    /** Shortens {@code text} with an ellipsis so it fits {@code maxWidth}. */
    static String fit(Font font, float size, String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (PdfDocument.width(font, size, text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        int end = text.length();
        while (end > 0 && PdfDocument.width(font, size, text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + ellipsis;
    }

    /** Word-wraps {@code text} (honouring line breaks); a word wider than the line is hard-broken. */
    static List<String> wrap(Font font, float size, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (PdfDocument.width(font, size, candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (PdfDocument.width(font, size, rest) > maxWidth) {
                    int cut = rest.length();
                    while (cut > 1 && PdfDocument.width(font, size, rest.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    /** A table cell: plain text, a coloured status chip, or a stacked passed/failed/skipped bar. */
    private record Cell(String text, int rgb, boolean bold, boolean chip, int[] bar) {
        Cell(String text, int rgb, boolean bold) {
            this(text, rgb, bold, false, null);
        }

        static Cell of(String text) {
            return new Cell(text, DEFAULT_COLOR, false);
        }

        static Cell chip(String text, int rgb) {
            return new Cell(text, rgb, true, true, null);
        }

        static Cell bar(int passed, int failed, int skipped) {
            return new Cell("", DEFAULT_COLOR, false, false, new int[] {passed, failed, skipped});
        }
    }

    private record Shot(byte[] jpeg, int width, int height) {
    }

    /** One rendering pass; holds the cursor (current page and y position). */
    private static final class Renderer {
        private final PdfDocument doc = new PdfDocument();
        private final PdfReportData data;
        private final PdfReportSettings cfg;
        private PdfDocument.Page page;
        private float y;
        private int shotsEmbedded;

        Renderer(PdfReportData data, PdfReportSettings cfg) {
            this.data = data;
            this.cfg = cfg;
        }

        byte[] build() {
            page = doc.newPage();
            header();
            summary();
            keyFindings();
            environment();
            byClass();
            failures();
            skipped();
            results();
            footers();
            String subject = join(" | ", data.site(), data.browser(), data.suite());
            return doc.toBytes(data.title(), "Selenium Automation Framework", subject, data.generatedAt());
        }

        // ---- sections ------------------------------------------------------------------------

        private void header() {
            float bandHeight = 96f;
            page.fillRect(0, PdfDocument.PAGE_HEIGHT - bandHeight, PdfDocument.PAGE_WIDTH, bandHeight, NAVY);

            String status = data.status();
            float badgeWidth = PdfDocument.width(Font.BOLD, 11, status) + 26f;
            float badgeHeight = 24f;
            float badgeX = PdfDocument.PAGE_WIDTH - MARGIN - badgeWidth;
            float badgeY = PdfDocument.PAGE_HEIGHT - 56f;
            page.fillRect(badgeX, badgeY, badgeWidth, badgeHeight, statusColor(status));
            page.text(Font.BOLD, 11, badgeX + 13f, badgeY + 8f, status, WHITE);

            float titleWidth = CONTENT_WIDTH - badgeWidth - 18f;
            page.text(Font.BOLD, 20, MARGIN, PdfDocument.PAGE_HEIGHT - 44f,
                fit(Font.BOLD, 20, data.title(), titleWidth), WHITE);
            String subtitle = join("  |  ", data.site(), data.browser(), data.suite());
            page.text(Font.REGULAR, 10.5f, MARGIN, PdfDocument.PAGE_HEIGHT - 68f,
                fit(Font.REGULAR, 10.5f, subtitle, CONTENT_WIDTH), 0xC7D2E8);

            y = PdfDocument.PAGE_HEIGHT - bandHeight - 22f;
            String meta = "Generated " + STAMP.format(data.generatedAt())
                + (data.durationMs() > 0 ? "     |     Duration " + duration(data.durationMs()) : "");
            page.text(Font.REGULAR, 9, MARGIN, y, meta, MUTED);
            y -= 14f;
        }

        private void summary() {
            heading("Executive summary");

            // Plain-language verdict, colour-coded, so a non-technical reader gets the answer first.
            String verdict = verdict();
            int tint = data.total() == 0 ? GREY_TINT : data.failed() > 0 ? RED_TINT : GREEN_TINT;
            int accent = statusColor(data.status());
            List<String> lines = wrap(Font.REGULAR, 10, verdict, CONTENT_WIDTH - 28f);
            float panelHeight = 16f + lines.size() * 14f;
            page.fillRect(MARGIN, y - panelHeight, CONTENT_WIDTH, panelHeight, tint);
            page.fillRect(MARGIN, y - panelHeight, 4f, panelHeight, accent);
            float ty = y - 17f;
            for (String line : lines) {
                page.text(Font.REGULAR, 10, MARGIN + 16f, ty, line, TEXT);
                ty -= 14f;
            }
            y -= panelHeight + 16f;

            // Donut (share of each outcome) on the left, the four numbers on the right.
            float top = y;
            float cx = MARGIN + 62f;
            float cy = top - 62f;
            donut(cx, cy, 56f, 36f);
            String pct = String.format(Locale.ROOT, "%.0f%%", data.passRate());
            page.text(Font.BOLD, 17, cx - PdfDocument.width(Font.BOLD, 17, pct) / 2f, cy - 2f, pct, NAVY);
            page.text(Font.REGULAR, 7.5f, cx - PdfDocument.width(Font.REGULAR, 7.5f, "passed") / 2f, cy - 13f,
                "passed", MUTED);

            float gap = 10f;
            float left = MARGIN + 140f;
            float cardWidth = (MARGIN + CONTENT_WIDTH - left - gap) / 2f;
            float cardHeight = 52f;
            String[] labels = {"TOTAL TESTS", "PASSED", "FAILED", "SKIPPED"};
            int[] values = {data.total(), data.passed(), data.failed(), data.skipped()};
            int[] colors = {NAVY, GREEN, RED, AMBER};
            for (int i = 0; i < 4; i++) {
                float x = left + (i % 2) * (cardWidth + gap);
                float cardTop = top - (i / 2) * (cardHeight + gap);
                page.fillRect(x, cardTop - cardHeight, cardWidth, cardHeight, LIGHT);
                page.fillRect(x, cardTop - cardHeight, 4f, cardHeight, colors[i]);
                page.text(Font.BOLD, 7.5f, x + 14f, cardTop - 16f, labels[i], MUTED);
                page.text(Font.BOLD, 22, x + 14f, cardTop - 41f, Integer.toString(values[i]), colors[i]);
            }
            y = top - 2 * cardHeight - gap - 18f;

            // How to read the statuses - the three words used throughout the report.
            ensureSpace(60f);
            page.text(Font.BOLD, 8, MARGIN, y - 4f, "HOW TO READ THIS REPORT", MUTED);
            y -= 12f;
            legend("PASSED", GREEN, "The test ran and every check succeeded.");
            legend("FAILED", RED, "A check did not hold or something broke. Needs a look; details are in the Failures section.");
            legend("SKIPPED", AMBER, "The test did not run (switched off, quarantined, or a step it depends on failed).");
            y -= 8f;
        }

        private String verdict() {
            if (data.total() == 0) {
                return "No tests were executed in this run.";
            }
            StringBuilder sb = new StringBuilder("Overall result: ").append(data.status()).append(". ")
                .append(data.passed()).append(" of ").append(data.total()).append(" tests passed (")
                .append(String.format(Locale.ROOT, "%.1f%%", data.passRate())).append("). ");
            sb.append(data.failed() > 0
                ? data.failed() + (data.failed() == 1 ? " test failed and needs attention. " : " tests failed and need attention. ")
                : "There were no failures. ");
            if (data.skipped() > 0) {
                sb.append(data.skipped()).append(data.skipped() == 1 ? " test was skipped (not run). " : " tests were skipped (not run). ");
            }
            if (data.durationMs() > 0) {
                sb.append("The run took ").append(duration(data.durationMs())).append('.');
            }
            return sb.toString().trim();
        }

        private void legend(String label, int rgb, String meaning) {
            ensureSpace(14f);
            float chipWidth = PdfDocument.width(Font.BOLD, 7, label) + 12f;
            page.fillRect(MARGIN, y - 11f, chipWidth, 11f, rgb);
            page.text(Font.BOLD, 7, MARGIN + 6f, y - 8.5f, label, WHITE);
            page.text(Font.REGULAR, 8.5f, MARGIN + 66f, y - 8.5f, fit(Font.REGULAR, 8.5f, meaning, CONTENT_WIDTH - 70f), TEXT);
            y -= 15f;
        }

        /** Ring chart of passed / failed / skipped, starting at 12 o'clock and running clockwise. */
        private void donut(float cx, float cy, float outer, float inner) {
            int total = data.total();
            if (total == 0) {
                page.polygon(ring(cx, cy, outer, inner, 90.0, -270.0), BORDER);
                return;
            }
            int[] counts = {data.passed(), data.failed(), data.skipped()};
            int[] colors = {GREEN, RED, AMBER};
            double start = 90.0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) {
                    continue;
                }
                double sweep = -360.0 * counts[i] / total;
                page.polygon(ring(cx, cy, outer, inner, start, sweep), colors[i]);
                start += sweep;
            }
        }

        private static float[] ring(float cx, float cy, float outer, float inner, double startDeg, double sweepDeg) {
            int steps = Math.max(2, (int) Math.ceil(Math.abs(sweepDeg) / 3.0));
            float[] pts = new float[(steps + 1) * 4];
            int k = 0;
            for (int i = 0; i <= steps; i++) {
                double a = Math.toRadians(startDeg + sweepDeg * i / steps);
                pts[k++] = cx + (float) (outer * Math.cos(a));
                pts[k++] = cy + (float) (outer * Math.sin(a));
            }
            for (int i = steps; i >= 0; i--) {
                double a = Math.toRadians(startDeg + sweepDeg * i / steps);
                pts[k++] = cx + (float) (inner * Math.cos(a));
                pts[k++] = cy + (float) (inner * Math.sin(a));
            }
            return pts;
        }

        /** What most needs attention: the most common failure causes, and the slowest tests. */
        private void keyFindings() {
            List<TestEntry> failed = new ArrayList<>();
            for (TestEntry t : data.tests()) {
                if (t.status() == Status.FAILED) {
                    failed.add(t);
                }
            }
            boolean slowWorth = data.tests().stream().anyMatch(t -> t.durationMs() > 0);
            if (failed.isEmpty() && !slowWorth) {
                return;
            }
            heading("Key findings");
            if (!failed.isEmpty()) {
                Map<String, List<TestEntry>> groups = new LinkedHashMap<>();
                for (TestEntry t : failed) {
                    groups.computeIfAbsent(causeKey(t.message()), k -> new ArrayList<>()).add(t);
                }
                List<List<TestEntry>> ranked = new ArrayList<>(groups.values());
                ranked.sort((x, y2) -> Integer.compare(y2.size(), x.size()));
                page.text(Font.BOLD, 9, MARGIN, y - 4f, "Most common failure causes", NAVY);
                y -= 12f;
                List<Cell[]> rows = new ArrayList<>();
                for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                    List<TestEntry> g = ranked.get(i);
                    StringBuilder names = new StringBuilder();
                    for (int j = 0; j < Math.min(3, g.size()); j++) {
                        names.append(j > 0 ? ", " : "").append(g.get(j).name());
                    }
                    if (g.size() > 3) {
                        names.append(" +").append(g.size() - 3).append(" more");
                    }
                    rows.add(new Cell[] {new Cell(Integer.toString(g.size()), RED, true),
                        Cell.of(shortCause(g.get(0).message())), Cell.of(names.toString())});
                }
                table(new String[] {"Tests", "Cause", "Affected tests"}, new float[] {42f, 250f, CONTENT_WIDTH - 292f},
                    new boolean[] {true, false, false}, rows);
            }
            if (slowWorth) {
                List<TestEntry> slow = new ArrayList<>(data.tests());
                slow.sort((p, q) -> Long.compare(q.durationMs(), p.durationMs()));
                page.text(Font.BOLD, 9, MARGIN, y - 4f, "Slowest tests", NAVY);
                y -= 12f;
                List<Cell[]> rows = new ArrayList<>();
                for (int i = 0; i < Math.min(5, slow.size()); i++) {
                    TestEntry t = slow.get(i);
                    rows.add(new Cell[] {Cell.of(t.name()), Cell.of(t.className()), Cell.of(duration(t.durationMs()))});
                }
                table(new String[] {"Test", "Class", "Time"}, new float[] {235f, 180f, CONTENT_WIDTH - 415f},
                    new boolean[] {false, false, true}, rows);
            }
        }

        /** Groups failures that are really the same problem: numbers and long tails are ignored. */
        private static String causeKey(String message) {
            if (message == null || message.isBlank()) {
                return "(no message)";
            }
            String s = message.replaceAll("\\d+", "#");
            int colon = s.indexOf(':');
            String cls = colon > 0 ? s.substring(0, colon) : s;
            String rest = colon > 0 ? s.substring(colon + 1).trim() : "";
            return cls + (rest.isEmpty() ? "" : ": " + (rest.length() > 70 ? rest.substring(0, 70) : rest));
        }

        /** {@code org.x.NoSuchElementException: no such element} -> {@code NoSuchElementException: no such element}. */
        private static String shortCause(String message) {
            if (message == null || message.isBlank()) {
                return "(no message)";
            }
            return message.replaceFirst("^[\\w.$]+\\.(\\w+)(:|$)", "$1$2");
        }

        private void environment() {
            if (data.environment().isEmpty()) {
                return;
            }
            heading("Environment");
            int row = 0;
            for (Map.Entry<String, String> e : data.environment().entrySet()) {
                ensureSpace(ROW_HEIGHT);
                if (row++ % 2 == 1) {
                    page.fillRect(MARGIN, y - ROW_HEIGHT + 3f, CONTENT_WIDTH, ROW_HEIGHT, LIGHT);
                }
                page.text(Font.BOLD, 8.5f, MARGIN + 6f, y - 8f, fit(Font.BOLD, 8.5f, e.getKey(), 110f), MUTED);
                page.text(Font.REGULAR, 8.5f, MARGIN + 130f, y - 8f,
                    fit(Font.REGULAR, 8.5f, e.getValue(), CONTENT_WIDTH - 136f), TEXT);
                y -= ROW_HEIGHT;
            }
            y -= 8f;
        }

        private void byClass() {
            if (data.tests().isEmpty()) {
                return;
            }
            Map<String, int[]> perClass = new LinkedHashMap<>();
            Map<String, Long> time = new LinkedHashMap<>();
            for (TestEntry t : data.tests()) {
                int[] c = perClass.computeIfAbsent(t.className(), k -> new int[4]);
                c[0]++;
                c[t.status() == Status.PASSED ? 1 : t.status() == Status.FAILED ? 2 : 3]++;
                time.merge(t.className(), t.durationMs(), Long::sum);
            }
            heading("Results by class");
            List<Cell[]> rows = new ArrayList<>();
            for (Map.Entry<String, int[]> e : perClass.entrySet()) {
                int[] c = e.getValue();
                rows.add(new Cell[] {
                    Cell.of(e.getKey().isEmpty() ? "(none)" : e.getKey()),
                    Cell.of(Integer.toString(c[0])),
                    new Cell(Integer.toString(c[1]), c[1] > 0 ? GREEN : DEFAULT_COLOR, false),
                    new Cell(Integer.toString(c[2]), c[2] > 0 ? RED : DEFAULT_COLOR, c[2] > 0),
                    new Cell(Integer.toString(c[3]), c[3] > 0 ? AMBER : DEFAULT_COLOR, false),
                    Cell.of(duration(time.get(e.getKey()))),
                    Cell.bar(c[1], c[2], c[3])});
            }
            float[] widths = {150f, 42f, 46f, 44f, 50f, 58f, 0f};
            widths[6] = CONTENT_WIDTH - 390f;
            table(new String[] {"Class", "Total", "Passed", "Failed", "Skipped", "Time", "Distribution"}, widths,
                new boolean[] {false, true, true, true, true, true, false}, rows);
        }

        private void failures() {
            List<TestEntry> failed = new ArrayList<>();
            for (TestEntry t : data.tests()) {
                if (t.status() == Status.FAILED) {
                    failed.add(t);
                }
            }
            if (failed.isEmpty()) {
                return;
            }
            heading("Failures (" + failed.size() + ")");
            int shown = Math.min(failed.size(), cfg.maxFailureDetails());
            for (int i = 0; i < shown; i++) {
                failureBlock(i + 1, failed.get(i));
            }
            if (failed.size() > shown) {
                ensureSpace(14f);
                page.text(Font.REGULAR, 8.5f, MARGIN, y - 8f, (failed.size() - shown)
                    + " more failure(s) are listed in the results table below without details.", MUTED);
                y -= 22f;
            }
        }

        private void failureBlock(int index, TestEntry t) {
            float headerHeight = 24f;
            ensureSpace(headerHeight + 40f);
            float top = y;
            page.fillRect(MARGIN, top - headerHeight, CONTENT_WIDTH, headerHeight, RED_TINT);
            page.fillRect(MARGIN, top - headerHeight, 3f, headerHeight, RED);
            String right = (t.className().isEmpty() ? "" : t.className() + "  |  ") + duration(t.durationMs());
            float rightWidth = PdfDocument.width(Font.REGULAR, 8, right);
            page.text(Font.REGULAR, 8, MARGIN + CONTENT_WIDTH - rightWidth - 8f, top - 15f,
                right, MUTED);
            page.text(Font.BOLD, 9.5f, MARGIN + 10f, top - 15f,
                fit(Font.BOLD, 9.5f, "#" + index + "  " + t.name(), CONTENT_WIDTH - rightWidth - 28f), TEXT);
            y = top - headerHeight - 10f;

            if (!t.message().isBlank()) {
                List<String> lines = wrap(Font.MONO, 8, t.message(), CONTENT_WIDTH - 14f);
                int limit = 6;
                for (int i = 0; i < Math.min(lines.size(), limit); i++) {
                    String line = lines.get(i);
                    if (i == limit - 1 && lines.size() > limit) {
                        line = fit(Font.MONO, 8, line + " ...", CONTENT_WIDTH - 14f);
                    }
                    ensureSpace(11f);
                    page.text(Font.MONO, 8, MARGIN + 8f, y - 8f, line, TEXT);
                    y -= 11f;
                }
                y -= 4f;
            }

            if (!t.stackTrace().isBlank() && cfg.stackTraceLines() > 0) {
                ensureSpace(24f);
                page.text(Font.BOLD, 7.5f, MARGIN + 8f, y - 7f, "Stack trace", MUTED);
                y -= 12f;
                int printed = 0;
                for (String line : t.stackTrace().split("\\R")) {
                    if (printed++ >= cfg.stackTraceLines()) {
                        break;
                    }
                    ensureSpace(9f);
                    page.text(Font.MONO, 7, MARGIN + 8f, y - 6f,
                        fit(Font.MONO, 7, line, CONTENT_WIDTH - 14f), MUTED);
                    y -= 9f;
                }
                y -= 4f;
            }

            if (cfg.screenshots() && t.screenshot() != null && shotsEmbedded < cfg.maxScreenshots()) {
                Shot shot = prepare(t.screenshot());
                if (shot != null) {
                    float scale = Math.min(CONTENT_WIDTH / shot.width(), MAX_SHOT_HEIGHT / shot.height());
                    float w = shot.width() * scale;
                    float h = shot.height() * scale;
                    ensureSpace(h + 22f);
                    // Named after the test: the image can land on the next page, away from its block.
                    page.text(Font.BOLD, 7.5f, MARGIN + 8f, y - 7f, fit(Font.BOLD, 7.5f,
                        "Screenshot at time of failure  |  #" + index + "  " + t.name(), CONTENT_WIDTH - 16f), MUTED);
                    y -= 12f;
                    int id = doc.addJpeg(shot.jpeg(), shot.width(), shot.height());
                    page.image(id, MARGIN, y - h, w, h);
                    page.strokeRect(MARGIN, y - h, w, h, BORDER, 0.5f);
                    y -= h + 6f;
                    shotsEmbedded++;
                }
            }
            y -= 14f;
        }

        private void skipped() {
            List<TestEntry> skipped = new ArrayList<>();
            for (TestEntry t : data.tests()) {
                if (t.status() == Status.SKIPPED && !t.message().isBlank()) {
                    skipped.add(t);
                }
            }
            if (skipped.isEmpty()) {
                return;
            }
            heading("Skipped tests (" + skipped.size() + ")");
            int limit = 40;
            for (int i = 0; i < Math.min(skipped.size(), limit); i++) {
                TestEntry t = skipped.get(i);
                ensureSpace(ROW_HEIGHT);
                String name = fit(Font.BOLD, 8.5f, t.name(), 200f);
                float nameWidth = PdfDocument.width(Font.BOLD, 8.5f, name);
                page.text(Font.BOLD, 8.5f, MARGIN + 6f, y - 8f, name, TEXT);
                page.text(Font.REGULAR, 8.5f, MARGIN + 6f + nameWidth + 8f, y - 8f,
                    fit(Font.REGULAR, 8.5f, t.message(), CONTENT_WIDTH - nameWidth - 20f), MUTED);
                y -= ROW_HEIGHT;
            }
            if (skipped.size() > limit) {
                ensureSpace(ROW_HEIGHT);
                page.text(Font.REGULAR, 8.5f, MARGIN + 6f, y - 8f,
                    "... and " + (skipped.size() - limit) + " more", MUTED);
                y -= ROW_HEIGHT;
            }
            y -= 8f;
        }

        private void results() {
            List<TestEntry> shown = new ArrayList<>();
            for (TestEntry t : data.tests()) {
                if (cfg.includePassed() || t.status() != Status.PASSED) {
                    shown.add(t);
                }
            }
            if (data.tests().isEmpty()) {
                heading("Results");
                page.text(Font.REGULAR, 9, MARGIN, y - 8f, "No tests were executed.", MUTED);
                y -= 20f;
                return;
            }
            if (shown.isEmpty()) {
                return;
            }
            heading(cfg.includePassed() ? "All results" : "Failed and skipped tests");
            List<Cell[]> rows = new ArrayList<>();
            int n = 1;
            for (TestEntry t : shown) {
                rows.add(new Cell[] {
                    Cell.of(Integer.toString(n++)),
                    Cell.of(t.name()),
                    Cell.of(t.className()),
                    Cell.chip(t.status().name(), statusColor(t.status().name())),
                    Cell.of(duration(t.durationMs()))});
            }
            float[] widths = {26f, 215f, 150f, 58f, 0f};
            widths[4] = CONTENT_WIDTH - 449f;
            table(new String[] {"#", "Test", "Class", "Status", "Time"}, widths,
                new boolean[] {true, false, false, false, true}, rows);
        }

        private void footers() {
            int count = doc.pageCount();
            String left = data.title() + "  |  generated " + STAMP.format(data.generatedAt());
            for (int i = 0; i < count; i++) {
                PdfDocument.Page p = doc.page(i);
                p.line(MARGIN, 46f, PdfDocument.PAGE_WIDTH - MARGIN, 46f, BORDER, 0.5f);
                String pageLabel = "Page " + (i + 1) + " of " + count;
                float labelWidth = PdfDocument.width(Font.REGULAR, 7.5f, pageLabel);
                p.text(Font.REGULAR, 7.5f, MARGIN, 34f, fit(Font.REGULAR, 7.5f, left,
                    CONTENT_WIDTH - labelWidth - 16f), MUTED);
                p.text(Font.REGULAR, 7.5f, PdfDocument.PAGE_WIDTH - MARGIN - labelWidth, 34f, pageLabel, MUTED);
            }
        }

        // ---- building blocks -----------------------------------------------------------------

        private void heading(String title) {
            ensureSpace(60f);
            doc.addBookmark(title, doc.pageCount() - 1, y);
            y -= 10f;
            page.text(Font.BOLD, 13, MARGIN, y - 4f, title, NAVY);
            page.line(MARGIN, y - 10f, MARGIN + CONTENT_WIDTH, y - 10f, NAVY, 1f);
            y -= 24f;
        }

        /** Starts a new page when {@code height} does not fit above the footer. */
        private boolean ensureSpace(float height) {
            if (y - height >= BOTTOM) {
                return false;
            }
            page = doc.newPage();
            y = TOP;
            return true;
        }

        private void table(String[] headers, float[] widths, boolean[] rightAligned, List<Cell[]> rows) {
            drawTableHeader(headers, widths, rightAligned);
            int row = 0;
            for (Cell[] cells : rows) {
                if (ensureSpace(ROW_HEIGHT + 2f)) {
                    drawTableHeader(headers, widths, rightAligned);
                }
                if (row++ % 2 == 1) {
                    page.fillRect(MARGIN, y - ROW_HEIGHT, CONTENT_WIDTH, ROW_HEIGHT, LIGHT);
                }
                float x = MARGIN;
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = cells[c];
                    if (cell.bar() != null) {
                        drawBar(x + 5f, y - 11f, widths[c] - 10f, 7f, cell.bar());
                        x += widths[c];
                        continue;
                    }
                    if (cell.chip()) {
                        float chipWidth = PdfDocument.width(Font.BOLD, 7, cell.text()) + 10f;
                        page.fillRect(x + 5f, y - 12.5f, chipWidth, 10.5f, cell.rgb());
                        page.text(Font.BOLD, 7, x + 10f, y - 9.5f, cell.text(), WHITE);
                        x += widths[c];
                        continue;
                    }
                    Font font = cell.bold() ? Font.BOLD : Font.REGULAR;
                    String text = fit(font, 8.5f, cell.text(), widths[c] - 10f);
                    float tx = rightAligned[c] ? x + widths[c] - 5f - PdfDocument.width(font, 8.5f, text) : x + 5f;
                    page.text(font, 8.5f, tx, y - 10.5f, text, cell.rgb() == DEFAULT_COLOR ? TEXT : cell.rgb());
                    x += widths[c];
                }
                y -= ROW_HEIGHT;
            }
            page.line(MARGIN, y, MARGIN + CONTENT_WIDTH, y, BORDER, 0.5f);
            y -= 14f;
        }

        /** Stacked passed/failed/skipped bar; an empty grey track when the class has no tests. */
        private void drawBar(float x, float yBottom, float width, float height, int[] counts) {
            int sum = counts[0] + counts[1] + counts[2];
            page.fillRect(x, yBottom, width, height, BORDER);
            if (sum == 0) {
                return;
            }
            int[] colors = {GREEN, RED, AMBER};
            float cursor = x;
            for (int i = 0; i < 3; i++) {
                float w = width * counts[i] / sum;
                if (w > 0) {
                    page.fillRect(cursor, yBottom, w, height, colors[i]);
                    cursor += w;
                }
            }
        }

        private void drawTableHeader(String[] headers, float[] widths, boolean[] rightAligned) {
            float height = 17f;
            page.fillRect(MARGIN, y - height, CONTENT_WIDTH, height, TABLE_HEAD);
            float x = MARGIN;
            for (int c = 0; c < headers.length; c++) {
                float tx = rightAligned[c] ? x + widths[c] - 5f - PdfDocument.width(Font.BOLD, 8, headers[c]) : x + 5f;
                page.text(Font.BOLD, 8, tx, y - 11.5f, headers[c], WHITE);
                x += widths[c];
            }
            y -= height;
        }

        /** Decodes a screenshot, caps its pixel width and re-encodes it as an RGB JPEG; null if unreadable. */
        private Shot prepare(byte[] image) {
            try {
                BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
                if (source == null || source.getWidth() < 1 || source.getHeight() < 1) {
                    return null;
                }
                int width = source.getWidth();
                int height = source.getHeight();
                int max = cfg.screenshotMaxWidthPx();
                if (width > max) {
                    height = Math.max(1, Math.round(height * (max / (float) width)));
                    width = max;
                }
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, width, height);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g.drawImage(source, 0, 0, width, height, null);
                } finally {
                    g.dispose();
                }
                return new Shot(toJpeg(rgb), width, height);
            } catch (IOException | RuntimeException e) {
                logger.warn("[PdfReport] Skipping an unreadable screenshot: {}", e.getMessage());
                return null;
            }
        }

        private static byte[] toJpeg(BufferedImage image) throws IOException {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.8f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                    writer.setOutput(out);
                    writer.write(null, new IIOImage(image, null, null), param);
                }
                return bos.toByteArray();
            } finally {
                writer.dispose();
            }
        }

        private static int statusColor(String status) {
            return switch (status) {
                case "PASSED" -> GREEN;
                case "FAILED" -> RED;
                case "SKIPPED" -> AMBER;
                default -> GREY_STATUS;
            };
        }

        private static String join(String separator, String... parts) {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(separator);
                    }
                    sb.append(part);
                }
            }
            return sb.toString();
        }
    }
}
