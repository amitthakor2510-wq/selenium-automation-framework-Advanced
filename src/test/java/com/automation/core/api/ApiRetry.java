package com.automation.core.api;

import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Retries a single API call a bounded number of times with exponential
 * backoff, for the narrow case that's actually worth retrying: a
 * transient failure (timeout/connection reset, thrown as an exception by
 * RestAssured) or a 5xx server error — never a 4xx, which is a
 * deterministic client-side/contract problem no retry will fix.
 *
 * Deliberately NOT wired into every ApiClient call automatically (see
 * ApiConfig#retryCount()'s own javadoc) — most of this project's API
 * assertions (BookStoreApiTest's 9-call chained flow, for one) want a
 * genuine failure to surface immediately, not be silently retried and
 * potentially mask a real regression. Call this explicitly at the one or
 * two call sites that actually need it, e.g. a flaky third-party
 * dependency:
 *
 * &lt;pre&gt;
 *   Response response = ApiRetry.withRetry(() -&gt;
 *       ApiClient.request().when().get("/flaky-endpoint")
 *   );
 * &lt;/pre&gt;
 *
 * Retry count/backoff default to {@link ApiConfig#retryCount()}/
 * {@link ApiConfig#retryBackoffMs()} but can be overridden per call via
 * the four-argument overload.
 */
public final class ApiRetry {

    private static final Logger logger = LoggerFactory.getLogger(ApiRetry.class);

    private ApiRetry() {
    }

    /** Retries using the configured default count/backoff (see ApiConfig). */
    public static Response withRetry(Supplier<Response> call) {
        return withRetry(call, ApiConfig.retryCount(), ApiConfig.retryBackoffMs());
    }

    /**
     * @param call         the API call to attempt (e.g. a RestAssured chain
     *                     ending in {@code .when().get(...)})
     * @param maxRetries   number of retries AFTER the first attempt (0 = no
     *                     retry, same as calling {@code call.get()} directly)
     * @param baseBackoffMs backoff before the first retry; doubles each
     *                      subsequent attempt (attempt 1: baseBackoffMs,
     *                      attempt 2: baseBackoffMs*2, attempt 3:
     *                      baseBackoffMs*4, ...) — standard exponential
     *                      backoff, keeps a flaky endpoint from being
     *                      hammered at a fixed interval.
     */
    public static Response withRetry(Supplier<Response> call, int maxRetries, long baseBackoffMs) {
        RuntimeException lastFailure = null;
        Response lastResponse = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Response response = call.get();
                int status = response.statusCode();
                if (status < 500) {
                    // 2xx/3xx/4xx — either success or a deterministic client
                    // error; neither is worth retrying, return immediately.
                    return response;
                }
                lastResponse = response;
                lastFailure = null;
                logger.warn("[ApiRetry] Attempt {}/{} got HTTP {} — {}",
                    attempt + 1, maxRetries + 1, status,
                    attempt < maxRetries ? "retrying" : "giving up, returning last response");
            } catch (RuntimeException e) {
                lastFailure = e;
                logger.warn("[ApiRetry] Attempt {}/{} threw {} — {}",
                    attempt + 1, maxRetries + 1, e.getClass().getSimpleName(),
                    attempt < maxRetries ? "retrying" : "giving up, rethrowing");
            }

            if (attempt < maxRetries) {
                sleep(baseBackoffMs * (1L << attempt));
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        // Exhausted retries on a persistent 5xx without ever throwing —
        // return the last (failing) response so the caller's own
        // assertions produce a normal, readable failure message (e.g.
        // "expected 200 but was 503") instead of this method inventing
        // its own exception type for what's ultimately still a valid,
        // if unwelcome, HTTP response.
        return lastResponse;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off between API retries", e);
        }
    }
}
