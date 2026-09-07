package com.automation.core.api;

import com.automation.core.config.ConfigReader;

/**
 * Resolves the base URI an API test class should hit, and a handful of
 * other API-wide settings (retry, timeout) — all through the same
 * three-layer ConfigReader mechanism (global.properties -&gt; {site}.properties
 * -&gt; -D system property) every other part of this framework already uses,
 * so API tests need zero new config-loading machinery.
 *
 * TWO WAYS an API test class gets a base URI, both resolving to the same
 * "url" config key:
 *
 *  1. SITE-COUPLED — the common case for a site that has both UI and API
 *     coverage (e.g. demoqa: BookStoreApiTest tests DemoQA's REST API,
 *     reusing the same demoqa.properties "url" the UI tests navigate to).
 *     Just run with -Dsite=&lt;site&gt; as usual.
 *
 *  2. STANDALONE / API-ONLY — a site that only ever exists for API
 *     testing, with no page objects, no object repository, no browser
 *     involved at all (e.g. "jsonplaceholder", see
 *     config/jsonplaceholder.properties and
 *     sites/jsonplaceholder/tests/JsonPlaceholderApiTest.java). This is
 *     still just a normal SiteRegistry entry with
 *     requiresObjectRepository=false — see SiteRegistry's own javadoc —
 *     so it gets the exact same on/off toggle
 *     (pipeline-config.properties), the exact same "missing config file"
 *     safety net, and the exact same -Dsite=&lt;name&gt; run command as every
 *     UI site. Nothing API-specific had to be bolted onto SiteRegistry or
 *     ConfigReader to support this — it's the same "site" concept, simply
 *     never paired with a page-object package.
 *
 * Either way, ApiConfig just calls ConfigReader.get("url") — there's no
 * separate "api.baseUrl" key to keep in sync with it. One base-URI concept
 * per site, reused by both testing styles.
 */
public final class ApiConfig {

    private ApiConfig() {
    }

    /** The active site's base URI (its config file's "url" key). */
    public static String baseUri() {
        return ConfigReader.get("url");
    }

    /**
     * Number of times a failed API call is retried via {@link ApiRetry}
     * before giving up. Default 0 (no retry) — most API failures are
     * genuine contract violations, not transient network blips, so
     * retrying is opt-in per call site via ApiRetry, not automatic for
     * every request ApiClient sends. This just centralizes the *default*
     * count callers can read instead of hardcoding a magic number.
     * Override with -Dapi.retry.count=N or set it in {site}.properties
     * for a flaky third-party API.
     */
    public static int retryCount() {
        return ConfigReader.getInt("api.retry.count", 0);
    }

    /**
     * Base backoff (milliseconds) ApiRetry waits before the first retry,
     * doubling on each subsequent attempt (see ApiRetry's own javadoc for
     * the exact backoff formula). Override with -Dapi.retry.backoffMs=N.
     */
    public static long retryBackoffMs() {
        return ConfigReader.getInt("api.retry.backoffMs", 500);
    }

    /**
     * Default max-acceptable response time (milliseconds) for
     * ApiAssertions#assertResponseTimeUnder(Response) when no explicit
     * threshold is passed. Override with -Dapi.responseTime.maxMs=N.
     */
    public static long defaultMaxResponseTimeMs() {
        return ConfigReader.getInt("api.responseTime.maxMs", 5000);
    }
}
