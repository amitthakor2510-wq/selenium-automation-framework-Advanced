package com.automation.core.api;

import io.qameta.allure.Allure;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.Response;
import org.testng.Assert;

import java.util.Arrays;

/**
 * Small, composable assertion helpers for API responses — the checks
 * that would otherwise get hand-rolled slightly differently in every new
 * API test class (status code with a readable failure message, JSON
 * schema, response-time budget, a header's presence/value). None of this
 * replaces RestAssured's own {@code .then().body(...)}/Hamcrest chain —
 * BookStoreApiTest's field-by-field assertions stay exactly as they are —
 * this is for the handful of cross-cutting checks worth a one-line call
 * instead of a few lines of Assert/Hamcrest boilerplate repeated per test.
 *
 * Every method returns the {@link Response} it was given so calls can be
 * chained: {@code ApiAssertions.assertStatus(response, 200).jsonPath()...}.
 */
public final class ApiAssertions {

    private ApiAssertions() {
    }

    /** Asserts the exact status code, with the response body attached to the failure message. */
    public static Response assertStatus(Response response, int expectedStatus) {
        int actual = response.statusCode();
        if (actual != expectedStatus) {
            Assert.fail("Expected HTTP " + expectedStatus + " but got HTTP " + actual
                + " — body: " + safeBody(response));
        }
        return response;
    }

    /** Asserts the status code is one of several acceptable values (e.g. 200 or 201). */
    public static Response assertStatusIn(Response response, int... expectedStatuses) {
        int actual = response.statusCode();
        boolean matched = Arrays.stream(expectedStatuses).anyMatch(s -> s == actual);
        if (!matched) {
            Assert.fail("Expected HTTP status in " + Arrays.toString(expectedStatuses)
                + " but got HTTP " + actual + " — body: " + safeBody(response));
        }
        return response;
    }

    /**
     * Asserts the response's whole shape against a JSON schema on the
     * classpath — same underlying check as
     * {@code .then().body(matchesJsonSchemaInClasspath(...))} used
     * directly in BookStoreApiTest, offered here as a standalone
     * assertion for test classes that build their Response via
     * {@code .extract().response()} rather than staying inside a
     * {@code .then()} chain (e.g. after {@link ApiRetry#withRetry}).
     */
    public static Response assertMatchesSchema(Response response, String classpathSchemaPath) {
        response.then().assertThat().body(JsonSchemaValidator.matchesJsonSchemaInClasspath(classpathSchemaPath));
        return response;
    }

    /**
     * Asserts the response came back within {@code maxMillis}. Attaches
     * the actual time to Allure either way so a passing-but-slow call is
     * still visible in the report, not just a failing one.
     */
    public static Response assertResponseTimeUnder(Response response, long maxMillis) {
        long actual = response.getTime();
        Allure.addAttachment("Response time", actual + " ms (budget: " + maxMillis + " ms)");
        if (actual > maxMillis) {
            Assert.fail("Response took " + actual + " ms, expected under " + maxMillis + " ms");
        }
        return response;
    }

    /** Same as {@link #assertResponseTimeUnder(Response, long)} using the configured default budget. */
    public static Response assertResponseTimeUnder(Response response) {
        return assertResponseTimeUnder(response, ApiConfig.defaultMaxResponseTimeMs());
    }

    /** Asserts a header is present, regardless of its value. */
    public static Response assertHeaderPresent(Response response, String headerName) {
        if (response.getHeader(headerName) == null) {
            Assert.fail("Expected header '" + headerName + "' to be present — headers were: "
                + response.getHeaders());
        }
        return response;
    }

    /** Asserts a header is present AND equals the expected value exactly. */
    public static Response assertHeaderEquals(Response response, String headerName, String expectedValue) {
        String actual = response.getHeader(headerName);
        if (!java.util.Objects.equals(actual, expectedValue)) {
            Assert.fail("Expected header '" + headerName + "' to equal '" + expectedValue
                + "' but was '" + actual + "'");
        }
        return response;
    }

    /** Asserts the JSON body has a non-empty array at the given JsonPath expression. */
    public static Response assertJsonArrayNotEmpty(Response response, String jsonPath) {
        java.util.List<?> list = response.jsonPath().getList(jsonPath);
        if (list == null || list.isEmpty()) {
            Assert.fail("Expected a non-empty array at JsonPath '" + jsonPath + "' — body: " + safeBody(response));
        }
        return response;
    }

    private static String safeBody(Response response) {
        try {
            String body = response.getBody().asString();
            // Keep failure messages readable — a large response body would
            // otherwise flood the TestNG/CI failure output.
            return body.length() > 2000 ? body.substring(0, 2000) + "... (truncated)" : body;
        } catch (RuntimeException e) {
            return "<unavailable: " + e.getMessage() + ">";
        }
    }
}
