package com.automation.core.api;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.Assert;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Black-box API security checks that are safe to run against a test environment: they
 * send ordinary requests with odd headers or parameters and look at how the API reacts.
 * Nothing here is destructive (the injection payloads are non-destructive probes).
 *
 * This is a first line of defence, not a penetration test. It catches the common
 * misses: endpoints that work without a token, tokens that aren't really verified,
 * stack traces in error bodies, missing hardening headers, permissive CORS.
 *
 * Only run it against systems you are authorised to test. Each assert* method collects
 * every problem it finds and fails once with the full list.
 */
public final class ApiSecurityChecks {

    private ApiSecurityChecks() {
    }

    private static final Map<String, Pattern> LEAK_SIGNATURES = new LinkedHashMap<>();

    static {
        LEAK_SIGNATURES.put("Java stack trace", Pattern.compile("\\bat [\\w$.]+\\([\\w$.]+\\.java:\\d+\\)"));
        LEAK_SIGNATURES.put("Python traceback", Pattern.compile("Traceback \\(most recent call last\\)"));
        LEAK_SIGNATURES.put("exception dump", Pattern.compile("(?i)exception in thread|\\bstack ?trace\\b"));
        LEAK_SIGNATURES.put(".NET exception", Pattern.compile("System\\.[A-Za-z.]*Exception"));
        LEAK_SIGNATURES.put("SQL error", Pattern.compile("(?i)sqlstate|ora-\\d{5}|you have an error in your sql syntax|unclosed quotation mark|psql:|pg_query"));
        LEAK_SIGNATURES.put("server file path", Pattern.compile("node_modules/|/usr/(local/)?lib/|/var/www/|[A-Za-z]:\\\\[\\w .-]+\\\\"));
    }

    private static final String[] INJECTION_PAYLOADS = {
        "' OR '1'='1",
        "\" OR \"\"=\"",
        "1' OR '1'='1' -- ",
        "'; --",
        "<script>alert(1)</script>",
        "\"><img src=x onerror=alert(1)>",
        "../../../../etc/passwd",
        "..\\..\\..\\windows\\win.ini",
        "%00",
        "${7*7}"
    };

    // ------------------------------------------------------------ authentication

    /** No Authorization header at all must be refused with 401 or 403. */
    public static void assertRejectsMissingAuth(String method, String path) {
        Response response = send(method, path, Map.of());
        expectStatusIn(response, "No credentials sent to " + method + " " + path, 401, 403);
    }

    /** A garbage bearer token must be refused (400, 401 or 403 - but never 2xx). */
    public static void assertRejectsInvalidToken(String method, String path) {
        Response response = send(method, path, Map.of("Authorization", "Bearer not.a.validtoken"));
        expectStatusIn(response, "Invalid bearer token sent to " + method + " " + path, 400, 401, 403);
    }

    /** A real JWT with its signature altered must be refused - proves the signature is actually verified. */
    public static void assertRejectsTamperedJwt(String method, String path, String validJwt) {
        String[] parts = validJwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Not a three-part JWT");
        }
        String sig = parts[2];
        char flipped = sig.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + flipped + sig.substring(1);
        Response response = send(method, path, Map.of("Authorization", "Bearer " + tampered));
        expectStatusIn(response, "JWT with altered signature sent to " + method + " " + path, 400, 401, 403);
    }

    /** The classic "alg":"none" unsigned token must be refused. */
    public static void assertRejectsAlgNoneJwt(String method, String path, String validJwt) {
        String[] parts = validJwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Not a three-part JWT");
        }
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + parts[1] + ".";
        Response response = send(method, path, Map.of("Authorization", "Bearer " + unsigned));
        expectStatusIn(response, "Unsigned (alg=none) JWT sent to " + method + " " + path, 400, 401, 403);
    }

    // ------------------------------------------------------------ response hardening

    /** nosniff, no X-Powered-By, no versioned Server banner, and HSTS when the API is served over HTTPS. */
    public static void assertSecurityHeaders(Response response) {
        List<String> findings = new ArrayList<>();
        String nosniff = response.getHeader("X-Content-Type-Options");
        if (nosniff == null || !nosniff.equalsIgnoreCase("nosniff")) {
            findings.add("X-Content-Type-Options: nosniff is missing");
        }
        if (ApiConfig.realBaseUri().toLowerCase().startsWith("https")
                && response.getHeader("Strict-Transport-Security") == null) {
            findings.add("Strict-Transport-Security (HSTS) is missing on an HTTPS API");
        }
        if (response.getHeader("X-Powered-By") != null) {
            findings.add("X-Powered-By leaks the technology stack: " + response.getHeader("X-Powered-By"));
        }
        String server = response.getHeader("Server");
        if (server != null && server.matches(".*\\d+\\.\\d+.*")) {
            findings.add("Server header leaks a version number: " + server);
        }
        failIfAny("Security header findings", findings);
    }

    /** An error response must not expose stack traces, SQL errors or server file paths. */
    public static void assertNoErrorLeak(Response response) {
        failIfAny("Error response leaks internals (HTTP " + response.statusCode() + ")", leakFindings(response));
    }

    /** A request for a path that does not exist must answer 4xx and leak nothing. */
    public static void assertUnknownPathFailsCleanly() {
        Response response = send("GET", "/this-path-should-not-exist-" + UUID.randomUUID(), Map.of());
        List<String> findings = new ArrayList<>(leakFindings(response));
        if (response.statusCode() >= 500) {
            findings.add("Unknown path answered HTTP " + response.statusCode() + " instead of a 4xx");
        }
        failIfAny("Unknown path handling", findings);
    }

    /** Sends probe payloads in {@code queryParam}; the API must not 5xx, leak internals, or reflect markup in HTML. */
    public static void assertInjectionPayloadsHandled(String path, String queryParam) {
        List<String> findings = new ArrayList<>();
        for (String payload : INJECTION_PAYLOADS) {
            Response response = ApiClient.request().queryParam(queryParam, payload).when().get(path);
            String body = safeBody(response);
            String contentType = String.valueOf(response.getContentType()).toLowerCase();
            if (response.statusCode() >= 500) {
                findings.add("HTTP " + response.statusCode() + " for payload [" + payload + "]");
            }
            for (String leak : leakFindings(response)) {
                findings.add(leak + " for payload [" + payload + "]");
            }
            if (contentType.contains("html") && body.contains(payload)) {
                findings.add("Payload reflected unescaped in an HTML response: [" + payload + "]");
            }
            if (body.contains("root:x:0:0") || body.toLowerCase().contains("[fonts]")) {
                findings.add("Path traversal payload returned file content: [" + payload + "]");
            }
        }
        failIfAny("Injection probe findings for " + path + "?" + queryParam + "=...", findings);
    }

    /** A hostile Origin must not be granted access (reflected, or allowed together with credentials). */
    public static void assertCorsNotOverPermissive(String path) {
        String evil = "https://evil.example.com";
        Response response = send("GET", path, Map.of("Origin", evil));
        String allowOrigin = response.getHeader("Access-Control-Allow-Origin");
        String allowCredentials = response.getHeader("Access-Control-Allow-Credentials");
        List<String> findings = new ArrayList<>();
        if (evil.equals(allowOrigin)) {
            findings.add("Arbitrary Origin is reflected in Access-Control-Allow-Origin"
                + ("true".equalsIgnoreCase(allowCredentials) ? " WITH Allow-Credentials: true" : ""));
        }
        if ("*".equals(allowOrigin) && "true".equalsIgnoreCase(allowCredentials)) {
            findings.add("Access-Control-Allow-Origin: * combined with Allow-Credentials: true");
        }
        failIfAny("CORS findings for " + path, findings);
    }

    /** HTTP TRACE must not be answered 200. */
    public static void assertTraceDisabled(String path) {
        Response response = send("TRACE", path, Map.of());
        if (response.statusCode() == 200) {
            Assert.fail("HTTP TRACE is enabled on " + path + " (answered 200).");
        }
    }

    // ------------------------------------------------------------ internals

    private static Response send(String method, String path, Map<String, String> headers) {
        RequestSpecification spec = ApiClient.request();
        if (!headers.isEmpty()) {
            spec = spec.headers(new LinkedHashMap<String, Object>(headers));
        }
        return spec.when().request(method, path);
    }

    private static void expectStatusIn(Response response, String what, int... allowed) {
        for (int status : allowed) {
            if (response.statusCode() == status) {
                return;
            }
        }
        Assert.fail(what + ": expected HTTP " + java.util.Arrays.toString(allowed)
            + " but got HTTP " + response.statusCode() + " - the endpoint is not enforcing authentication correctly.");
    }

    private static List<String> leakFindings(Response response) {
        List<String> findings = new ArrayList<>();
        String body = safeBody(response);
        LEAK_SIGNATURES.forEach((name, pattern) -> {
            if (pattern.matcher(body).find()) {
                findings.add(name + " found in response body");
            }
        });
        return findings;
    }

    private static String safeBody(Response response) {
        try {
            return response.asString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void failIfAny(String title, List<String> findings) {
        if (!findings.isEmpty()) {
            Assert.fail(title + ":\n - " + String.join("\n - ", findings));
        }
    }
}
