package com.automation.core.api.auth;

import io.restassured.specification.RequestSpecification;

/**
 * HTTP Basic auth (RFC 7617) — {@code Authorization: Basic
 * base64(username:password)}. Delegates to RestAssured's own
 * {@code .auth().preemptive().basic(...)} rather than encoding the
 * header by hand: "preemptive" sends the credentials on the first
 * request instead of waiting for a 401 challenge-response round trip,
 * which is what every real API using Basic auth in this project's
 * likely-future use (an internal/admin endpoint, a third-party service
 * that documents Basic auth) actually expects.
 */
public final class BasicAuthProvider implements AuthProvider {

    private final String username;
    private final String password;

    public BasicAuthProvider(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Basic auth username must not be null/blank");
        }
        this.username = username;
        this.password = password == null ? "" : password;
    }

    @Override
    public RequestSpecification apply(RequestSpecification request) {
        return request.auth().preemptive().basic(username, password);
    }
}
