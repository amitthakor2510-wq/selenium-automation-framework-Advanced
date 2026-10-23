package com.automation.core.api.auth;

import io.restassured.specification.RequestSpecification;

/**
 * {@code Authorization: Bearer &lt;token&gt;} — the scheme
 * {@code ApiClient.authorizedRequest(String)} already hand-rolled inline
 * for BookStoreApiTest's token flow. This is the same behavior lifted
 * into a reusable AuthProvider so any API test class can compose it via
 * {@code ApiClient.authenticatedRequest(new BearerTokenAuthProvider(token))}
 * instead of every call site repeating the header literal.
 * {@code ApiClient.authorizedRequest(String)} itself is unchanged and
 * still works exactly as before — this is an additive alternative for
 * test classes that also want the other AuthProvider machinery (e.g.
 * combining auth with ApiRetry), not a replacement.
 */
public final class BearerTokenAuthProvider implements AuthProvider {

    private final String token;

    public BearerTokenAuthProvider(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Bearer token must not be null/blank");
        }
        this.token = token;
    }

    @Override
    public RequestSpecification apply(RequestSpecification request) {
        return request.header("Authorization", "Bearer " + token);
    }
}
