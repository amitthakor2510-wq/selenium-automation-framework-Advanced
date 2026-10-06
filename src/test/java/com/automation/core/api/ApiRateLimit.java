package com.automation.core.api;

import io.qameta.allure.Allure;
import io.restassured.response.Response;
import org.testng.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Rate-limit test helper: fire a burst of identical requests, then assert on how the
 * API throttled them (HTTP 429, Retry-After header, no 5xx while throttling, recovery).
 *
 * A burst is real load. Only point it at APIs you are allowed to test, and keep the
 * request count modest (tens, not thousands). For proper load testing use the JMeter
 * perf suites already in this framework.
 */
public final class ApiRateLimit {

    private ApiRateLimit() {
    }

    /**
     * @param firstThrottledRequest 1-based position (in submission order) of the first 429, or -1
     * @param retryAfter            Retry-After header of the first 429, or null
     */
    public record Result(int total, Map<Integer, Integer> statusCounts, int throttledCount,
                         int firstThrottledRequest, String retryAfter, int serverErrorCount,
                         long elapsedMs) {

        public String describe() {
            return total + " requests in " + elapsedMs + " ms -> status counts " + statusCounts
                + (throttledCount > 0
                    ? "; first 429 at request #" + firstThrottledRequest
                        + ", Retry-After=" + (retryAfter == null ? "<absent>" : retryAfter)
                    : "; no 429 seen");
        }
    }

    /** Fires {@code requests} calls using {@code threads} worker threads and summarises the status codes. */
    public static Result burst(Supplier<Response> call, int requests, int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        long start = System.nanoTime();
        try {
            Callable<Response> task = call::get;
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(task));
            }

            Map<Integer, Integer> counts = new TreeMap<>();
            int throttled = 0;
            int firstThrottled = -1;
            int serverErrors = 0;
            String retryAfter = null;

            for (int i = 0; i < futures.size(); i++) {
                Response response;
                try {
                    response = futures.get(i).get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Burst request #" + (i + 1) + " threw: " + e.getCause(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during burst", e);
                }
                int status = response.statusCode();
                counts.merge(status, 1, Integer::sum);
                if (status == 429) {
                    throttled++;
                    if (firstThrottled < 0) {
                        firstThrottled = i + 1;
                        retryAfter = response.getHeader("Retry-After");
                    }
                }
                if (status >= 500) {
                    serverErrors++;
                }
            }

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            Result result = new Result(requests, counts, throttled, firstThrottled, retryAfter, serverErrors, elapsedMs);
            Allure.addAttachment("Rate-limit burst summary", result.describe());
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The API must start answering 429 at some point during the burst. */
    public static void assertThrottled(Result result) {
        if (result.throttledCount() == 0) {
            Assert.fail("Expected the API to throttle (HTTP 429) but it never did. " + result.describe()
                + " - raise api.ratelimit.requests, or the endpoint has no rate limit.");
        }
    }

    /** For a burst that should stay under the limit: no 429 allowed. */
    public static void assertNotThrottled(Result result) {
        if (result.throttledCount() > 0) {
            Assert.fail("Expected no throttling but got " + result.throttledCount() + " x HTTP 429. " + result.describe());
        }
    }

    /** A 429 should tell the client when to retry. */
    public static void assertRetryAfterPresent(Result result) {
        assertThrottled(result);
        if (result.retryAfter() == null || result.retryAfter().isBlank()) {
            Assert.fail("HTTP 429 was returned without a Retry-After header. " + result.describe());
        }
    }

    /** Hitting the limit must produce 429s, never 5xx errors. */
    public static void assertNoServerErrors(Result result) {
        if (result.serverErrorCount() > 0) {
            Assert.fail("Burst caused " + result.serverErrorCount() + " server error(s) (HTTP 5xx). " + result.describe());
        }
    }

    /** After waiting {@code waitMs}, the API must accept a request again (not still 429). */
    public static void assertRecovers(Supplier<Response> call, long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the rate limit window to reset", e);
        }
        int status = call.get().statusCode();
        if (status == 429) {
            Assert.fail("Still throttled (HTTP 429) " + waitMs + " ms after the burst.");
        }
    }
}
