package com.automation.core.api;

import com.automation.core.config.ConfigReader;
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
 * Config key (optional, see global.properties for the pattern):
 *   api.log.onFailureOnly=true - only print request/response on assertion
 *                                failure (default true; keeps CI logs
 *                                readable on chained flows like
 *                                BookStoreApiTest's 9-call sequence)
 */
public final class ApiClient {

    private ApiClient() {
    }

    /**
     * Points RestAssured at this site's base URL (reuses the same "url"
     * key the UI tests already resolve through ConfigReader) and wires up
     * logging behavior from config. Call once per test class, typically
     * from a @BeforeClass in a class extending BaseApiTest.
     */
    public static void configure() {
        RestAssured.baseURI = ConfigReader.get("url");

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

    /** Plain request spec, no content-type forced — for simple GETs. */
    public static RequestSpecification request() {
        return RestAssured.given();
    }
}
