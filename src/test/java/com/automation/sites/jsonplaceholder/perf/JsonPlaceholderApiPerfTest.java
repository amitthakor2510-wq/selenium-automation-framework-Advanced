package com.automation.sites.jsonplaceholder.perf;

import com.automation.core.perf.PerfAssertions;
import com.automation.core.perf.PerfTestBase;
import org.testng.annotations.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Load-tests a single API endpoint — the API-testing equivalent of
 * DemoQaHomePagePerfTest's UI-page load test, proving the perf layer is
 * not tied to "load-testing a browsable page": {@link #runGetLoadTest}
 * resolves its URL the exact same way ApiClient does (both go through
 * ApiConfig -&gt; ConfigReader), so a perf test for an API endpoint and a
 * functional test for that same endpoint (see JsonPlaceholderApiTest)
 * share one config source of truth for the target site's base URI.
 *
 * Run standalone: {@code -Dsite=jsonplaceholder
 * -DsuiteXmlFile=testng-suites/jsonplaceholder-perf.xml -Dgroups=perf}
 */
public class JsonPlaceholderApiPerfTest extends PerfTestBase {

    @Test(groups = {"perf"},
        description = "PERF - GET /posts/1 under configured concurrent load")
    public void getPost_ShouldMeetResponseTimeAndErrorRateBudget() throws Exception {
        TestPlanStats stats = runGetLoadTest("jsonplaceholder-get-post", "/posts/1");

        PerfAssertions.assertSamplesRecorded(stats);
        PerfAssertions.assertErrorRateUnder(stats);
        PerfAssertions.assertP99Under(stats);
    }
}
