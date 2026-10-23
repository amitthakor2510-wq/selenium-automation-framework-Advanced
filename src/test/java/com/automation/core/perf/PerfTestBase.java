package com.automation.core.perf;

import com.automation.core.api.ApiConfig;
import com.automation.core.config.ConfigReader;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.threadgroups.DslDefaultThreadGroup;

import java.io.IOException;
import java.time.Duration;

import static us.abstracta.jmeter.javadsl.JmeterDsl.htmlReporter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

/**
 * Base class for load/performance tests, built on the JMeter Java DSL
 * (jmeter-java-dsl — see pom.xml) instead of hand-edited .jmx XML.
 *
 * WHY A JAVA DSL INSTEAD OF (OR ALONGSIDE) THE EXISTING perf/basic-smoke.jmx:
 * that static JMX file still works (run via {@code mvn verify -Pperf}, see
 * docs/performance-testing-guide.md) and is untouched by this change — it
 * remains the quickest way to eyeball a response-time smoke check or to
 * open the plan in the JMeter GUI. But a raw .jmx file can't reuse this
 * project's ConfigReader/SiteRegistry/reporting stack, can't be
 * code-reviewed as a diff the way Java can, and needs a second, JMeter-
 * specific Maven profile+plugin most of this project's contributors won't
 * touch often enough to stay fluent in. A perf test written as a normal
 * TestNG class instead gets, for free: the same {@code -Dsite=...}
 * config resolution as every UI/API test (via ApiConfig, itself just
 * ConfigReader), the same Allure/ExtentReports/ReportPortal reporting
 * every other test type already produces (allure-testng's listener
 * doesn't care that a passing/failing @Test method happens to run a
 * JMeter engine internally), the same RetryListener/parallel-execution
 * suite-XML mechanics, and — the actual "scalable to all types of
 * testing" point — proof that adding a genuinely new kind of test to
 * this framework is "write a *Test class extending the right Base*Test",
 * not "invent a new toolchain integration each time." See
 * docs/architecture.md's "Adding a New Test Type" section.
 *
 * USAGE — extend this class and call {@link #runGetLoadTest(String, String)}
 * (relative path resolved against {@link ApiConfig#baseUri()}, so the
 * same "url" config key UI and API tests already use) or
 * {@link #run(DslDefaultThreadGroup)} directly for a custom thread group
 * (multiple samplers, a non-GET request, think-time between steps, ...).
 * See DemoQaHomePagePerfTest (UI page load) and
 * JsonPlaceholderApiPerfTest (API endpoint) for both flavors.
 *
 * THREAD-GROUP SHAPE: uses rampTo(...).holdIterating(...) — ramps from 0
 * to {@link PerfConfig#threads()} over {@link PerfConfig#rampUpSeconds()},
 * then each thread runs {@link PerfConfig#iterations()} iterations. This
 * mirrors the threads/rampUp/iterations model the legacy .jmx file already
 * uses (see perf/basic-smoke.jmx), so the two approaches' results are
 * comparable rather than measuring different load shapes.
 */
public abstract class PerfTestBase {

    @BeforeClass(alwaysRun = true)
    public void setUpPerfConfig() {
        // Same call, same reasoning as BaseApiTest.setUpApiClient()/
        // BaseTest's own setup — picks up whatever -Dsite was passed for
        // *this* run rather than a value cached from a previous test
        // class in the same JVM. Deliberately NOT using @Parameters("site")
        // here: this project's suite XMLs pass the site via -Dsite (a
        // system property), not a TestNG <parameter> element, and every
        // other Base*Test class follows that same convention — see
        // BaseApiTest.java.
        ConfigReader.reset();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownPerfConfig() {
        ConfigReader.reset();
    }

    /**
     * Runs a simple single-sampler GET load test against
     * {@code ApiConfig.baseUri() + relativePath}, using the configured
     * thread/ramp-up/iteration profile (see PerfConfig), writes an HTML
     * report + JTL under {@code target/perf-reports/<reportName>/}, and
     * returns the stats for the caller to assert on via PerfAssertions.
     */
    protected TestPlanStats runGetLoadTest(String reportName, String relativePath) throws IOException {
        String url = ApiConfig.baseUri() + relativePath;
        DslDefaultThreadGroup group = threadGroup(reportName)
            .rampTo(PerfConfig.threads(), Duration.ofSeconds(PerfConfig.rampUpSeconds()))
            .holdIterating(PerfConfig.iterations())
            .children(httpSampler(reportName, url));
        return run(reportName, group);
    }

    /**
     * Runs a caller-built thread group (multiple samplers, custom
     * ramp-up/hold shape, think-time, ...) with the same HTML/JTL
     * reporting {@link #runGetLoadTest(String, String)} sets up. Use this
     * when a single httpSampler GET isn't enough to represent the
     * scenario — e.g. a login step followed by an authenticated page
     * load.
     */
    protected TestPlanStats run(String reportName, DslDefaultThreadGroup group) throws IOException {
        String reportPath = PerfConfig.reportBaseDir() + "/" + sanitize(reportName);
        return testPlan(
            group,
            jtlWriter(reportPath + "/jtls"),
            htmlReporter(reportPath + "/html")
        ).run();
    }

    private static String sanitize(String reportName) {
        // Report names become directory names — strip anything that
        // isn't filesystem-safe rather than trusting every call site to
        // pass an already-safe string.
        return reportName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
