package com.automation.core.api.generic;

import com.automation.core.api.ApiClient;
import com.automation.core.api.ApiSecurityChecks;
import com.automation.core.api.auth.RefreshingBearerAuthProvider;
import com.automation.core.config.ConfigReader;
import com.automation.sites.core.BaseApiTest;
import io.restassured.response.Response;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Config-driven API security checks - no code per project, just keys in config/{site}.properties.
 * Run with testng-suites/api-security.xml (see ApiSecurityChecks for what each check does).
 *
 *   api.security.protectedPaths = /users/me,/orders        endpoints that require a token
 *   api.security.method         = GET                      HTTP method used on protectedPaths
 *   api.security.publicPaths    = /                        paths for header / CORS / TRACE checks (default "/")
 *   api.security.injectionTargets = /search:q,/users:name  path:queryParam pairs for injection probes
 *   api.auth.*                  = see RefreshingBearerAuthProvider.fromConfig (needed for the JWT tamper checks)
 *
 * A check whose key is empty is skipped, and shows as SKIPPED in the report.
 */
public class GenericApiSecurityChecks extends BaseApiTest {

    private static List<String> csv(String key, String defaultValue) {
        return Arrays.stream(ConfigReader.get(key, defaultValue).split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Object[][] rows(List<String> values) {
        if (values.isEmpty()) {
            return new Object[][] {{""}};
        }
        Object[][] rows = new Object[values.size()][1];
        for (int i = 0; i < values.size(); i++) {
            rows[i][0] = values.get(i);
        }
        return rows;
    }

    private static String method() {
        return ConfigReader.get("api.security.method", "GET").trim().toUpperCase();
    }

    private static void requireValue(String value, String configKey) {
        if (value == null || value.isEmpty()) {
            throw new SkipException("Set " + configKey + " in config/" + ConfigReader.getActiveSite() + ".properties to enable this check");
        }
    }

    /** The user's real JWT, or a skip if there's no token / it isn't a JWT (opaque tokens can't be tampered with). */
    private static String jwtOrSkip() {
        Optional<RefreshingBearerAuthProvider> provider = RefreshingBearerAuthProvider.fromConfig();
        if (provider.isEmpty()) {
            throw new SkipException("Set api.auth.type (and its keys) to enable JWT tamper checks");
        }
        String token = provider.get().currentToken();
        if (token.split("\\.").length != 3) {
            throw new SkipException("The access token is opaque (not a three-part JWT) - tamper checks don't apply");
        }
        return token;
    }

    @DataProvider(name = "protectedPaths")
    public Object[][] protectedPaths() {
        return rows(csv("api.security.protectedPaths", ""));
    }

    @DataProvider(name = "publicPaths")
    public Object[][] publicPaths() {
        return rows(csv("api.security.publicPaths", "/"));
    }

    @DataProvider(name = "injectionTargets")
    public Object[][] injectionTargets() {
        return rows(csv("api.security.injectionTargets", ""));
    }

    // ---------------------------------------------------------------- authentication

    @Test(dataProvider = "protectedPaths", groups = {"security", "api"},
        description = "Security - protected endpoint rejects a request with no credentials")
    public void protectedEndpoint_ShouldRejectMissingAuth(String path) {
        requireValue(path, "api.security.protectedPaths");
        ApiSecurityChecks.assertRejectsMissingAuth(method(), path);
    }

    @Test(dataProvider = "protectedPaths", groups = {"security", "api"},
        description = "Security - protected endpoint rejects a garbage bearer token")
    public void protectedEndpoint_ShouldRejectInvalidToken(String path) {
        requireValue(path, "api.security.protectedPaths");
        ApiSecurityChecks.assertRejectsInvalidToken(method(), path);
    }

    @Test(dataProvider = "protectedPaths", groups = {"security", "api"},
        description = "Security - protected endpoint rejects a JWT with a tampered signature")
    public void protectedEndpoint_ShouldRejectTamperedJwt(String path) {
        requireValue(path, "api.security.protectedPaths");
        ApiSecurityChecks.assertRejectsTamperedJwt(method(), path, jwtOrSkip());
    }

    @Test(dataProvider = "protectedPaths", groups = {"security", "api"},
        description = "Security - protected endpoint rejects an unsigned (alg=none) JWT")
    public void protectedEndpoint_ShouldRejectAlgNoneJwt(String path) {
        requireValue(path, "api.security.protectedPaths");
        ApiSecurityChecks.assertRejectsAlgNoneJwt(method(), path, jwtOrSkip());
    }

    // ---------------------------------------------------------------- hardening

    @Test(dataProvider = "publicPaths", groups = {"security", "api"},
        description = "Security - responses carry the expected hardening headers")
    public void response_ShouldCarrySecurityHeaders(String path) {
        Response response = ApiClient.get(path);
        ApiSecurityChecks.assertSecurityHeaders(response);
    }

    @Test(dataProvider = "publicPaths", groups = {"security", "api"},
        description = "Security - CORS does not grant a hostile origin access")
    public void cors_ShouldNotTrustArbitraryOrigins(String path) {
        ApiSecurityChecks.assertCorsNotOverPermissive(path);
    }

    @Test(dataProvider = "publicPaths", groups = {"security", "api"},
        description = "Security - HTTP TRACE is disabled")
    public void trace_ShouldBeDisabled(String path) {
        ApiSecurityChecks.assertTraceDisabled(path);
    }

    @Test(groups = {"security", "api"},
        description = "Security - an unknown path returns a clean 4xx without leaking internals")
    public void unknownPath_ShouldFailCleanly() {
        ApiSecurityChecks.assertUnknownPathFailsCleanly();
    }

    // ---------------------------------------------------------------- injection probes

    @Test(dataProvider = "injectionTargets", groups = {"security", "api"},
        description = "Security - injection / traversal probe payloads are handled safely")
    public void injectionProbes_ShouldBeHandledSafely(String target) {
        requireValue(target, "api.security.injectionTargets");
        int split = target.lastIndexOf(':');
        if (split <= 0 || split == target.length() - 1) {
            throw new IllegalArgumentException("api.security.injectionTargets entries must look like /path:queryParam, got: " + target);
        }
        ApiSecurityChecks.assertInjectionPayloadsHandled(target.substring(0, split), target.substring(split + 1));
    }
}
