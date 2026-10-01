package com.automation.core.api;

import com.automation.core.api.auth.AuthProvider;
import com.automation.core.config.ConfigReader;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.specification.RequestSpecification;

/**
 * Thin, reusable wrapper around RestAssured so API tests don't each
 * re-implement base-URI setup, auth headers, and request/response logging.
 *
 * Lives in src/test (not src/main/core, alongside DriverFactory) because
 * rest-assured is deliberately test-scoped in pom.xml — this class would
 * fail to compile under src/main. BaseApiTest is the TestNG glue that
 * calls into this from @BeforeClass.
 *
 * Works identically whether the active site (-Dsite=...) is a UI+API site
 * like demoqa (see BookStoreApiTest) or a pure API-only site with no page
 * objects at all (see ApiConfig's javadoc and
 * sites/jsonplaceholder/tests/JsonPlaceholderApiTest.java) — both resolve
 * their base URI the same way, through ApiConfig -&gt; ConfigReader.
 *
 * Config keys (optional, see global.properties for the pattern):
 *   api.log.onFailureOnly=true - only print request/response on assertion
 *                                failure (default true; keeps CI logs
 *                                readable on chained flows like
 *                                BookStoreApiTest's 9-call sequence)
 *   api.retry.count / api.retry.backoffMs - see ApiConfig/ApiRetry
 *   api.responseTime.maxMs - see ApiConfig/ApiAssertions
 */
public final class ApiClient {

    private ApiClient() {
    }

    /**
     * Points RestAssured at this site's base URL (reuses the same "url"
     * key the UI tests already resolve through ConfigReader, via
     * ApiConfig) and wires up logging + Allure request/response
     * attachment. Call once per test class, typically from a
     * @BeforeClass in a class extending BaseApiTest.
     *
     * The Allure filter is registered unconditionally (not gated behind
     * api.log.onFailureOnly) — unlike console request/response logging,
     * which floods CI output if left on for every call, an Allure
     * attachment costs nothing to a reader who isn't looking at the
     * report, and is exactly what you want available when you ARE
     * looking at a failed run: the full request/response for every step,
     * not just the ones that happened to fail.
     */
    public static void configure() {
        RestAssured.baseURI = ApiConfig.baseUri();
        RestAssured.filters(new AllureRestAssured());

        if (ConfigReader.getBoolean("api.log.onFailureOnly", true)) {
            RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        } else {
            RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        }
    }

    /** Fresh request spec with JSON content-type set — the common case. */
    public static RequestSpecification jsonRequest() {
        return RestAssured.given().contentType("application/json");
    }

    /** Same as {@link #jsonRequest()} plus a Bearer Authorization header. */
    public static RequestSpecification authorizedRequest(String bearerToken) {
        return jsonRequest().header("Authorization", "Bearer " + bearerToken);
    }

    /**
     * Same as {@link #jsonRequest()} with {@code provider} applied — the
     * general-purpose entry point for any {@link AuthProvider}
     * (Bearer/Basic/API-key/a custom scheme), not just the bearer-token
     * shortcut {@link #authorizedRequest(String)} already covers.
     * Prefer this for new test classes; authorizedRequest(String) stays
     * for BookStoreApiTest's existing call sites, unchanged.
     */
    public static RequestSpecification authenticatedRequest(AuthProvider provider) {
        return provider.apply(jsonRequest());
    }

    /** Plain request spec, no content-type forced — for simple GETs. */
    public static RequestSpecification request() {
        return RestAssured.given();
    }

    // ------------------------------------------------------------------
    // One-line HTTP verb helpers — sugar over jsonRequest()/request() for
    // the common "no special headers, just hit this path" case. These are
    // deliberately additive: every existing call site (BookStoreApiTest,
    // BookStoreApiNegativeTest) keeps building its own RequestSpecification
    // via jsonRequest()/authorizedRequest()/request() exactly as before —
    // nothing here changes those classes' behavior. New API test classes
    // (see JsonPlaceholderApiTest) can use whichever style reads better
    // for a given call: these one-liners for a plain request, or the
    // builder methods above when headers/auth/query params are needed.
    // ------------------------------------------------------------------

    /** GET {@code path} with no special headers. */
    public static io.restassured.response.Response get(String path) {
        return request().when().get(path);
    }

    /** POST {@code path} with a JSON body. */
    public static io.restassured.response.Response post(String path, Object body) {
        return jsonRequest().body(body).when().post(path);
    }

    /** PUT {@code path} with a JSON body. */
    public static io.restassured.response.Response put(String path, Object body) {
        return jsonRequest().body(body).when().put(path);
    }

    /** PATCH {@code path} with a JSON body. */
    public static io.restassured.response.Response patch(String path, Object body) {
        return jsonRequest().body(body).when().patch(path);
    }

    /** DELETE {@code path} with no body. */
    public static io.restassured.response.Response delete(String path) {
        return request().when().delete(path);
    }
}
