package com.automation.sites.listeners;

import com.automation.core.report.pdf.PdfEvidenceStore;
import com.automation.core.report.pdf.PdfReportData;
import com.automation.core.report.pdf.PdfReportData.Status;
import com.automation.core.report.pdf.PdfReportData.TestEntry;
import com.automation.core.report.pdf.PdfReportSettings;
import com.automation.core.report.pdf.ThrowableText;
import org.testng.ISuite;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns a finished TestNG {@link ISuite} into the {@link PdfReportData} the PDF writer renders. Lives
 * with the listeners (not in {@code core}) because TestNG is a test-scope dependency.
 *
 * <p>Counting matches {@code PostRunReportListener.summarise}: failed-within-success-percentage tests
 * count as failures and a SKIP that is only an earlier retry attempt is ignored. Failure messages and
 * stack traces are redacted ({@link ThrowableText}); data-driven parameters are never printed.
 */
final class PdfReportSuiteAdapter {

    private PdfReportSuiteAdapter() {
    }

    static PdfReportData fromSuite(ISuite suite, Instant started, PdfReportSettings settings,
                                   String site, String browser, Map<String, String> environment) {
        record Timed(long start, TestEntry entry) {
        }
        List<Timed> collected = new ArrayList<>();
        for (ISuiteResult suiteResult : suite.getResults().values()) {
            ITestContext ctx = suiteResult.getTestContext();
            for (ITestResult r : ctx.getPassedTests().getAllResults()) {
                collected.add(new Timed(r.getStartMillis(), entry(r, Status.PASSED, settings)));
            }
            for (ITestResult r : ctx.getFailedTests().getAllResults()) {
                collected.add(new Timed(r.getStartMillis(), entry(r, Status.FAILED, settings)));
            }
            for (ITestResult r : ctx.getFailedButWithinSuccessPercentageTests().getAllResults()) {
                collected.add(new Timed(r.getStartMillis(), entry(r, Status.FAILED, settings)));
            }
            for (ITestResult r : ctx.getSkippedTests().getAllResults()) {
                if (!r.wasRetried()) {
                    collected.add(new Timed(r.getStartMillis(), entry(r, Status.SKIPPED, settings)));
                }
            }
        }
        // Group by class (readable "Results by class"), then in the order the tests actually ran.
        collected.sort(Comparator.<Timed, String>comparing(t -> t.entry().className())
            .thenComparingLong(Timed::start));
        List<TestEntry> tests = new ArrayList<>(collected.size());
        for (Timed t : collected) {
            tests.add(t.entry());
        }
        long durationMs = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
        return new PdfReportData(settings.title(), site, browser, suite.getName(), Instant.now(), durationMs,
            environment, PdfReportData.uniqueNames(tests));
    }

    private static TestEntry entry(ITestResult r, Status status, PdfReportSettings settings) {
        Throwable t = r.getThrowable();
        byte[] screenshot = null;
        if (status != Status.PASSED && settings.screenshots()) {
            screenshot = PdfEvidenceStore.get(PdfEvidenceStore.key(r.getTestClass().getName(),
                r.getMethod().getMethodName(), r.getParameters()));
        }
        return new TestEntry(r.getMethod().getMethodName(), r.getTestClass().getRealClass().getSimpleName(),
            status, Math.max(0, r.getEndMillis() - r.getStartMillis()), ThrowableText.message(t),
            status == Status.PASSED ? "" : ThrowableText.stackTrace(t, settings.stackTraceLines()), screenshot);
    }
}
