package com.automation.sites.demoqa.tests;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import com.automation.core.api.ApiAssertions;
import com.automation.core.api.ApiClient;
import com.automation.core.api.ApiRetry;
import com.automation.core.mock.WireMockManager;
import com.automation.sites.core.BaseApiTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.restassured.response.Response;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Error states a real backend will never produce on demand — a 500, a slow response, an endpoint
 * that fails once and then recovers — used to check how THIS FRAMEWORK's API layer
 * ({@code ApiClient}, {@code ApiRetry}, {@code ApiAssertions}) reacts to them.
 *
 * <p>Requires the WireMock server: run through {@code testng-suites/api-tests-mocked.xml}, or pass
 * {@code -Dmock.enabled=true}. Without it the whole class is skipped (never failed) — it would
 * otherwise be trying to stub a server it isn't talking to.
 *
 * <p>Every stub here lives under {@code /__mock__/resilience/}, a path nothing else uses, because
 * the WireMock server is shared by every API test class in the JVM (which run in parallel). Stubs
 * are added, never reset, so this class can't disturb another class's data.
 *
 * <p>Deliberately no {@code smoke}/{@code regression} group: demoqa's UI suites package-scan this
 * folder and select by those groups, so tagging it would pull a mock-only class into a browser run.
 */
public class ApiResilienceMockedTest extends BaseApiTest {

    private static final String BASE = "/__mock__/resilience";

    @BeforeClass(alwaysRun = true)
    public void requireMockServer() {
        if (!WireMockManager.isEnabled()) {
            throw new SkipException("Needs the WireMock server — run with -Dmock.enabled=true "
                + "(or testng-suites/api-tests-mocked.xml)");
        }
    }

    @Test(groups = {"api", "mock"},
        description = "API resilience - a 500 comes back as a plain response the test can assert on")
    public void serverError_IsSurfacedNotSwallowed() {
        WireMockManager.server().stubFor(get(urlEqualTo(BASE + "/always-500")).willReturn(serverError()));

        Response response = ApiClient.get(BASE + "/always-500");

        assertEquals(response.statusCode(), 500);
        // The shared assertion helper must actually fail on it, with the status in the message.
        AssertionError failure = expectThrows(AssertionError.class,
            () -> ApiAssertions.assertStatus(response, 200));
        assertTrue(failure.getMessage().contains("500"),
            "Failure message should name the actual status, was: " + failure.getMessage());
    }

    @Test(groups = {"api", "mock"},
        description = "API resilience - ApiRetry recovers from a transient 503")
    public void retry_RecoversFromTransientFailure() {
        String path = BASE + "/flaky";
        WireMockManager.server().stubFor(get(urlEqualTo(path))
            .inScenario("flaky-endpoint").whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("recovered")
            .willReturn(serviceUnavailable()));
        WireMockManager.server().stubFor(get(urlEqualTo(path))
            .inScenario("flaky-endpoint").whenScenarioStateIs("recovered")
            .willReturn(ok("back")));

        Response response = ApiRetry.withRetry(() -> ApiClient.get(path), 2, 50);

        assertEquals(response.statusCode(), 200, "ApiRetry should have retried past the first 503");
        assertEquals(response.asString(), "back");
    }

    @Test(groups = {"api", "mock"},
        description = "API resilience - ApiRetry gives up on a persistent 500 and returns the last response")
    public void retry_GivesUpOnPersistentFailure() {
        String path = BASE + "/down";
        WireMockManager.server().stubFor(get(urlEqualTo(path)).willReturn(serverError()));

        Response response = ApiRetry.withRetry(() -> ApiClient.get(path), 2, 20);

        assertEquals(response.statusCode(), 500, "Should return the last failing response, not invent an exception");
        // 1 attempt + 2 retries — WireMock itself counts what actually reached it.
        WireMockManager.server().verify(3, getRequestedFor(urlEqualTo(path)));
    }

    @Test(groups = {"api", "mock"},
        description = "API resilience - a slow response trips the response-time budget")
    public void slowResponse_FailsResponseTimeBudget() {
        WireMockManager.server().stubFor(get(urlEqualTo(BASE + "/slow"))
            .willReturn(ok("late").withFixedDelay(800)));

        Response response = ApiClient.get(BASE + "/slow");

        assertEquals(response.statusCode(), 200);
        expectThrows(AssertionError.class, () -> ApiAssertions.assertResponseTimeUnder(response, 200));
    }
}
