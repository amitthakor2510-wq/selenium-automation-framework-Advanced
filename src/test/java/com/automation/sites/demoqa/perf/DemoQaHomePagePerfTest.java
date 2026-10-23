package com.automation.sites.demoqa.perf;

import com.automation.core.perf.PerfAssertions;
import com.automation.core.perf.PerfTestBase;
import org.testng.annotations.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Load-tests demoqa's home page over plain HTTP — a JMeter sampler hits
 * the page's URL directly, it does not drive a real browser/WebDriver.
 * That's a deliberate, standard distinction in performance testing: this
 * measures server/network response time under concurrent load, which is
 * what a load test is for; it says nothing about client-side rendering
 * time, which is what the UI test suite (DemoQaHomePage and friends,
 * under a real ChromeDriver/Grid session) already covers. Running both
 * kinds against the same page is complementary, not redundant.
 *
 * Run standalone: {@code -Dsite=demoqa
 * -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf}
 * (opt-in group, same convention as the synthetic-data tests — not part
 * of smoke/regression, see docs/performance-testing-guide.md).
 */
public class DemoQaHomePagePerfTest extends PerfTestBase {

    @Test(groups = {"perf"},
        description = "PERF - demoqa home page under configured concurrent load")
    public void homePage_ShouldMeetResponseTimeAndErrorRateBudget() throws Exception {
        TestPlanStats stats = runGetLoadTest("demoqa-home", "/");

        PerfAssertions.assertSamplesRecorded(stats);
        PerfAssertions.assertErrorRateUnder(stats);
        PerfAssertions.assertP99Under(stats);
    }
}
