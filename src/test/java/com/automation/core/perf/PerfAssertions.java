package com.automation.core.perf;

import io.qameta.allure.Allure;
import org.testng.Assert;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

import java.time.Duration;

/**
 * Threshold assertions against a {@link TestPlanStats} result — the load
 * equivalent of {@code com.automation.core.api.ApiAssertions} for
 * functional API checks. Deliberately only reads the small set of
 * TestPlanStats accessors this framework has actually confirmed the
 * behavior of (samplesCount/errorsCount/sampleTimePercentile99) rather
 * than guessing at a wider surface — see jmeter-java-dsl's own README/
 * user guide for the full StatsSummary API if a future need (throughput,
 * bytes/sec, other percentiles) calls for extending this class.
 *
 * Every method attaches the measured value to Allure before asserting,
 * so a passing run still shows the actual numbers in the report, not
 * just "PASS" — a load test's real numbers are worth seeing even when
 * they're comfortably under budget.
 */
public final class PerfAssertions {

    private PerfAssertions() {
    }

    /** Asserts the 99th-percentile response time is under {@code maxMillis}. */
    public static void assertP99Under(TestPlanStats stats, long maxMillis) {
        Duration p99 = stats.overall().sampleTimePercentile99();
        Allure.addAttachment("p99 response time",
            p99.toMillis() + " ms (budget: " + maxMillis + " ms)");
        if (p99.toMillis() > maxMillis) {
            Assert.fail("p99 response time was " + p99.toMillis()
                + " ms, expected under " + maxMillis + " ms");
        }
    }

    /** Same as {@link #assertP99Under(TestPlanStats, long)} using {@link PerfConfig#maxP99Millis()}. */
    public static void assertP99Under(TestPlanStats stats) {
        assertP99Under(stats, PerfConfig.maxP99Millis());
    }

    /** Asserts the overall error rate (errors / total samples * 100) is under {@code maxPercent}. */
    public static void assertErrorRateUnder(TestPlanStats stats, double maxPercent) {
        long samples = stats.overall().samplesCount();
        long errors = stats.overall().errorsCount();
        double errorRate = samples == 0 ? 0.0 : (errors * 100.0 / samples);
        Allure.addAttachment("Error rate",
            String.format("%.2f%% (%d/%d samples, budget: %.2f%%)", errorRate, errors, samples, maxPercent));
        if (errorRate > maxPercent) {
            Assert.fail(String.format("Error rate was %.2f%% (%d/%d samples), expected under %.2f%%",
                errorRate, errors, samples, maxPercent));
        }
    }

    /** Same as {@link #assertErrorRateUnder(TestPlanStats, double)} using {@link PerfConfig#maxErrorRatePercent()}. */
    public static void assertErrorRateUnder(TestPlanStats stats) {
        assertErrorRateUnder(stats, PerfConfig.maxErrorRatePercent());
    }

    /** Asserts at least one sample was actually recorded — catches a misconfigured plan silently running zero requests. */
    public static void assertSamplesRecorded(TestPlanStats stats) {
        long samples = stats.overall().samplesCount();
        if (samples == 0) {
            Assert.fail("Load test recorded 0 samples — check the thread group/sampler configuration");
        }
    }
}
