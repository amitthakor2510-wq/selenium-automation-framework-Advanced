package com.automation.core.report.pdf;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * PDF report configuration. Every key can come from (in order) a {@code -D} system property, the
 * properties files ({@code global.properties} / the site file) or an environment variable named after
 * the key ({@code report.pdf.enabled} -> {@code REPORT_PDF_ENABLED}). Same lookup rules as
 * {@code EmailSettings}, so both features are configured the same way.
 */
public final class PdfReportSettings {

    private final boolean enabled;
    private final Path outputDir;
    private final String title;
    private final boolean screenshots;
    private final int maxScreenshots;
    private final int screenshotMaxWidthPx;
    private final int stackTraceLines;
    private final boolean includePassed;
    private final int maxFailureDetails;

    private PdfReportSettings(UnaryOperator<String> lookup, Map<String, String> env) {
        this.enabled = bool(lookup, env, "report.pdf.enabled", false);
        this.outputDir = Path.of(str(lookup, env, "report.pdf.output.dir", "target/pdf-reports"));
        this.title = str(lookup, env, "report.pdf.title", "Test Execution Report");
        this.screenshots = bool(lookup, env, "report.pdf.screenshots", true);
        this.maxScreenshots = Math.max(0, integer(lookup, env, "report.pdf.screenshots.max", 25));
        this.screenshotMaxWidthPx = Math.max(200, integer(lookup, env, "report.pdf.screenshots.maxWidthPx", 1000));
        this.stackTraceLines = Math.max(0, integer(lookup, env, "report.pdf.stacktrace.lines", 10));
        this.includePassed = bool(lookup, env, "report.pdf.include.passed", true);
        this.maxFailureDetails = Math.max(1, integer(lookup, env, "report.pdf.failures.max", 50));
    }

    /** {@code lookup} returns a configured value or null/blank; {@code env} is the environment. */
    public static PdfReportSettings of(UnaryOperator<String> lookup, Map<String, String> env) {
        return new PdfReportSettings(lookup, env);
    }

    /** Defaults only (nothing enabled) - handy in tests. */
    public static PdfReportSettings defaults() {
        return new PdfReportSettings(k -> null, Map.of());
    }

    private static String str(UnaryOperator<String> lookup, Map<String, String> env, String key, String dflt) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            v = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private static boolean bool(UnaryOperator<String> lookup, Map<String, String> env, String key, boolean dflt) {
        return Boolean.parseBoolean(str(lookup, env, key, String.valueOf(dflt)));
    }

    private static int integer(UnaryOperator<String> lookup, Map<String, String> env, String key, int dflt) {
        try {
            return Integer.parseInt(str(lookup, env, key, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** {@code report.pdf.enabled} - OFF by default, like the other end-of-run reports. */
    public boolean enabled() {
        return enabled;
    }

    /** Directory the PDF is written to ({@code report.pdf.output.dir}). */
    public Path outputDir() {
        return outputDir;
    }

    public String title() {
        return title;
    }

    /** Embed failure screenshots ({@code report.pdf.screenshots}). */
    public boolean screenshots() {
        return screenshots;
    }

    /** At most this many screenshots are embedded, so the PDF (and an e-mail attachment) stays small. */
    public int maxScreenshots() {
        return maxScreenshots;
    }

    public int screenshotMaxWidthPx() {
        return screenshotMaxWidthPx;
    }

    /** Stack-trace lines shown per failure ({@code report.pdf.stacktrace.lines}); 0 hides the trace. */
    public int stackTraceLines() {
        return stackTraceLines;
    }

    /** List passed tests in the results table too; false keeps the PDF to failures and skips. */
    public boolean includePassed() {
        return includePassed;
    }

    /** Failures beyond this many appear in the results table only, not as detailed blocks. */
    public int maxFailureDetails() {
        return maxFailureDetails;
    }
}
