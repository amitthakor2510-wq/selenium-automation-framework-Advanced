package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;

import java.time.Instant;
import java.util.List;

/**
 * What the e-mail reports about one run.
 *
 * @param site         site under test (may be blank)
 * @param browser      browser (may be blank)
 * @param suite        suite name
 * @param total        executed + skipped test count
 * @param passed       passed tests
 * @param failed       failed tests
 * @param skipped      skipped tests (quarantined and not-run included; retried attempts excluded)
 * @param durationMs   wall-clock run time, 0 when unknown
 * @param startedAt    when the run began (attachments older than this are never sent)
 * @param failures     the failed tests, for the table in the body
 * @param ciLink       link to the CI run, or blank
 * @param license      dependency license counts, or null when no scan ran
 */
public record RunSummary(String site, String browser, String suite, int total, int passed, int failed,
                         int skipped, long durationMs, Instant startedAt, List<FailedTest> failures,
                         String ciLink, LicenseReportWriter.Summary license) {

    /** One failed test. {@code message} is already redacted/shortened by the builder. */
    public record FailedTest(String name, String className, String message) {
    }

    public RunSummary {
        failures = List.copyOf(failures);
    }

    public String status() {
        if (total == 0) {
            return "NO TESTS";
        }
        return failed > 0 ? "FAILED" : "PASSED";
    }

    public boolean hasProblems() {
        return failed > 0 || (license != null && license.failing() > 0);
    }
}
