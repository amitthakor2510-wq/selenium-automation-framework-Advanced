package com.automation.core.perf;

import com.automation.core.config.ConfigReader;

/**
 * Load-test parameters, resolved through the exact same three-layer
 * ConfigReader mechanism (global.properties -&gt; {site}.properties -&gt;
 * -D system property) every other test type in this framework already
 * uses — see ApiConfig for the API-testing equivalent. No new
 * config-loading machinery needed to add performance testing here; it's
 * the same site/config concept, a different set of keys.
 *
 * All defaults live in global.properties under the "PERFORMANCE TESTING"
 * section; override per-run with -Dperf.threads=... etc., or per-site by
 * adding the same key to a {site}.properties file (e.g. a site whose
 * real-world traffic profile is much higher than the framework default).
 */
public final class PerfConfig {

    private PerfConfig() {
    }

    /** Peak concurrent virtual users. Default 10. */
    public static int threads() {
        return ConfigReader.getInt("perf.threads", 10);
    }

    /** Seconds to ramp from 0 to {@link #threads()} — avoids a thundering-herd start. Default 5. */
    public static int rampUpSeconds() {
        return ConfigReader.getInt("perf.rampUpSeconds", 5);
    }

    /** Iterations each thread runs once ramp-up completes. Default 5. */
    public static int iterations() {
        return ConfigReader.getInt("perf.iterations", 5);
    }

    /** 99th-percentile response-time budget (ms) — see PerfAssertions#assertP99Under(TestPlanStats). Default 5000. */
    public static long maxP99Millis() {
        return ConfigReader.getInt("perf.maxP99Millis", 5000);
    }

    /** Maximum acceptable error rate (%) across the whole run — see PerfAssertions#assertErrorRateUnder(TestPlanStats). Default 1.0. */
    public static double maxErrorRatePercent() {
        return Double.parseDouble(ConfigReader.get("perf.maxErrorRatePercent", "1.0"));
    }

    /** Directory the Java-DSL HTML report is written under, one subfolder per test. Default target/perf-reports. */
    public static String reportBaseDir() {
        return ConfigReader.get("perf.reportBaseDir", "target/perf-reports");
    }
}
