package com.automation.core.api.auth;

import io.restassured.specification.RequestSpecification;

/**
 * API-key auth — the key travels as either a request header (the common
 * case, e.g. {@code X-API-Key: ...}) or a query parameter (some APIs,
 * especially older/simpler ones, expect {@code ?apiKey=...} instead).
 * Which one is chosen by which factory method is used, not a boolean
 * flag, so a call site reads unambiguously:
 *
 * &lt;pre&gt;
 *   ApiKeyAuthProvider.header("X-API-Key", key)
 *   ApiKeyAuthProvider.queryParam("apiKey", key)
 * &lt;/pre&gt;
 */
public final class ApiKeyAuthProvider implements AuthProvider {

    private enum Placement { HEADER, QUERY_PARAM }

    private final Placement placement;
    private final String paramName;
    private final String apiKey;

    private ApiKeyAuthProvider(Placement placement, String paramName, String apiKey) {
        if (paramName == null || paramName.isBlank()) {
            throw new IllegalArgumentException("API key parameter name must not be null/blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key value must not be null/blank");
        }
        this.placement = placement;
        this.paramName = paramName;
        this.apiKey = apiKey;
    }

    /** Sends the key as a request header, e.g. {@code X-API-Key: <key>}. */
    public static ApiKeyAuthProvider header(String headerName, String apiKey) {
        return new ApiKeyAuthProvider(Placement.HEADER, headerName, apiKey);
    }

    /** Sends the key as a query parameter, e.g. {@code ?apiKey=<key>}. */
    public static ApiKeyAuthProvider queryParam(String paramName, String apiKey) {
        return new ApiKeyAuthProvider(Placement.QUERY_PARAM, paramName, apiKey);
    }

    @Override
    public RequestSpecification apply(RequestSpecification request) {
        return placement == Placement.HEADER
            ? request.header(paramName, apiKey)
            : request.queryParam(paramName, apiKey);
    }
}
