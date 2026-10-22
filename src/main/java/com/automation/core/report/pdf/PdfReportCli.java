package com.automation.core.report.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Builds ONE consolidated PDF from every {@code testng-results.xml} under {@code report.pdf.results.dir}
 * (default {@code target}) - the CI "report" job's counterpart of the per-run PDF each test JVM writes.
 *
 * <p>Usage: {@code mvn -q process-test-resources compile exec:java@pdf-report -Ppdf-report}. Settings come
 * from {@code -D} properties, {@code config/global.properties} and environment variables (see
 * {@link PdfReportSettings}); {@code report.pdf.enabled} is NOT consulted because running this command is
 * the request. Output: {@code report.pdf.output.file}, default
 * {@code <report.pdf.output.dir>/consolidated-report.pdf}. Optional {@code -Dsite=...} / {@code -Dbrowser=...}
 * only label the report.
 *
 * <p>Exit status: 0 when the PDF was written, 1 when it could not be.
 */
public final class PdfReportCli {

    private PdfReportCli() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run();
        } catch (Exception e) {
            System.err.println("[PdfReport] Failed: " + e.getMessage());
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run() throws IOException {
        Properties defaults = loadGlobalDefaults();
        PdfReportSettings settings = PdfReportSettings.of(
            key -> System.getProperty(key) != null ? System.getProperty(key) : defaults.getProperty(key),
            System.getenv());

        Path resultsDir = Path.of(System.getProperty("report.pdf.results.dir", "target"));
        String outputFile = System.getProperty("report.pdf.output.file", "");
        Path output = outputFile.isBlank() ? settings.outputDir().resolve("consolidated-report.pdf") : Path.of(outputFile);

        PdfReportData data = TestNgXmlReportReader.readDirectory(resultsDir, settings.title(),
            System.getProperty("site", ""), System.getProperty("browser", ""), settings.stackTraceLines());
        if (data.total() == 0) {
            System.out.println("[PdfReport] No testng-results.xml with tests found under " + resultsDir
                + " - the report will say NO TESTS.");
        }
        PdfReportWriter.write(data, settings, output);
        System.out.println("[PdfReport] " + data.status() + ": " + data.passed() + " passed, " + data.failed()
            + " failed, " + data.skipped() + " skipped -> " + output.toAbsolutePath());
        return 0;
    }

    private static Properties loadGlobalDefaults() throws IOException {
        Properties props = new Properties();
        String explicit = System.getProperty("report.pdf.config");
        if (explicit != null && !explicit.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(explicit))) {
                props.load(in);
            }
            return props;
        }
        try (InputStream in = PdfReportCli.class.getClassLoader().getResourceAsStream("config/global.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        return props;
    }
}
