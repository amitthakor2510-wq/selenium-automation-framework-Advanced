package com.automation.core.report.pdf;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the PDF report shows about one run. Built either from a live TestNG suite
 * (the post-run listener) or from {@code testng-results.xml} files ({@link PdfReportCli}); the writer
 * does not care which.
 *
 * @param title       report title, shown in the header band
 * @param site        site under test (may be blank)
 * @param browser     browser (may be blank)
 * @param suite       suite name (may be blank)
 * @param generatedAt when the PDF was produced
 * @param durationMs  wall-clock run time, 0 when unknown
 * @param environment name/value pairs for the "Environment" table (insertion order is kept)
 * @param tests       one entry per executed test, in the order they should be listed
 */
public record PdfReportData(String title, String site, String browser, String suite, Instant generatedAt,
                            long durationMs, Map<String, String> environment, List<TestEntry> tests) {

    /** Outcome of one test. */
    public enum Status { PASSED, FAILED, SKIPPED }

    /**
     * One test.
     *
     * @param name        test method name (a repeat of the same name gets a {@code [#n]} suffix)
     * @param className   simple class name
     * @param status      outcome
     * @param durationMs  how long it ran
     * @param message     failure/skip reason, already redacted; blank for a pass
     * @param stackTrace  condensed stack trace, one frame per line, already redacted; may be blank
     * @param screenshot  PNG/JPEG bytes captured at the moment of failure; null when there is none
     */
    public record TestEntry(String name, String className, Status status, long durationMs, String message,
                            String stackTrace, byte[] screenshot) {

        public TestEntry {
            name = name == null ? "" : name;
            className = className == null ? "" : className;
            message = message == null ? "" : message;
            stackTrace = stackTrace == null ? "" : stackTrace;
        }
    }

    public PdfReportData {
        title = title == null || title.isBlank() ? "Test Execution Report" : title;
        site = site == null ? "" : site;
        browser = browser == null ? "" : browser;
        suite = suite == null ? "" : suite;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        environment = environment == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /**
     * Gives repeated names (data-driven rows run under one method name) a {@code [#n]} suffix, so rows in
     * the table stay distinguishable without printing the parameters (which can contain credentials).
     */
    public static List<TestEntry> uniqueNames(List<TestEntry> tests) {
        Map<String, Integer> total = new LinkedHashMap<>();
        for (TestEntry t : tests) {
            total.merge(t.className() + "#" + t.name(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<TestEntry> result = new java.util.ArrayList<>(tests.size());
        for (TestEntry t : tests) {
            String key = t.className() + "#" + t.name();
            if (total.get(key) > 1) {
                int n = seen.merge(key, 1, Integer::sum);
                t = new TestEntry(t.name() + " [#" + n + "]", t.className(), t.status(), t.durationMs(),
                    t.message(), t.stackTrace(), t.screenshot());
            }
            result.add(t);
        }
        return result;
    }

    public int total() {
        return tests.size();
    }

    public int passed() {
        return count(Status.PASSED);
    }

    public int failed() {
        return count(Status.FAILED);
    }

    public int skipped() {
        return count(Status.SKIPPED);
    }

    /** Share of tests that passed, 0-100; 0 when nothing ran. */
    public double passRate() {
        return total() == 0 ? 0 : passed() * 100.0 / total();
    }

    public String status() {
        if (total() == 0) {
            return "NO TESTS";
        }
        return failed() > 0 ? "FAILED" : "PASSED";
    }

    private int count(Status status) {
        int n = 0;
        for (TestEntry t : tests) {
            if (t.status() == status) {
                n++;
            }
        }
        return n;
    }
}
