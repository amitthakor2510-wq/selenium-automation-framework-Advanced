package com.automation.core.api.auth;

import io.restassured.specification.RequestSpecification;

/**
 * One authentication strategy applied to a RestAssured request spec.
 * Deliberately a single-method functional interface — every real-world
 * REST auth scheme (bearer token, basic auth, API key header/query
 * param, a custom HMAC signature, ...) boils down to "add these
 * headers/params/auth to the request before it's sent", so a single
 * apply(RequestSpecification) is enough to cover all of them without a
 * different interface per scheme.
 *
 * ApiClient composes an AuthProvider onto a request via
 * {@link com.automation.core.api.ApiClient#authenticatedRequest(AuthProvider)}
 * instead of exposing scheme-specific methods for every possible auth
 * type — adding a new scheme (OAuth2 client-credentials, HMAC signing,
 * whatever a new site needs) is a new AuthProvider implementation, never
 * a change to ApiClient itself.
 *
 * Implementations should be stateless/thread-safe where practical (this
 * framework's API suites run classes in parallel — see
 * testng-suites/api-tests.xml's thread-count) — the three provided
 * implementations (Bearer/Basic/ApiKey) hold only an immutable
 * credential string, so that's automatically satisfied.
 */
@FunctionalInterface
public interface AuthProvider {

    /**
     * Adds this provider's authentication to {@code request} and returns
     * it (typically the same, mutated instance — RestAssured's
     * RequestSpecification methods are themselves builder-style and
     * return {@code this}, so implementations can usually just chain off
     * the parameter and return it directly).
     */
    RequestSpecification apply(RequestSpecification request);
}
