package com.automation.core.api.spec;

import com.automation.core.api.ApiConfig;
import com.automation.core.api.auth.RefreshingBearerAuthProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.mock.WireMockManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.Header;
import io.restassured.http.Method;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one {@link ApiCase}: builds the request from the spec, sends it (with retry), checks the
 * response, extracts variables for later cases, registers cleanup, and records the outcome in the
 * {@link ApiRunReport}. Every check failure of a case is reported together in one assertion message.
 */
public final class SpecExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpecExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLEANUPS = "cleanups";
    private static final String VARS_READY = "varsReady";

    /** A fully-resolved HTTP request. */
    private static final class Prepared {
        String method;
        String base;
        String path;
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, Object> query = new LinkedHashMap<>();
        String bodyText;
        String contentType;
        Map<String, Object> form;
        Map<String, Object> multipart;
        int timeoutMs;
        boolean insecure;
        boolean follow = true;
        String fullUrl() {
            String p = path.startsWith("http") ? path : base + (path.startsWith("/") || path.isEmpty() ? "" : "/") + path;
            return p;
        }
    }

    private record Cleanup(String description, String method, String url, Map<String, String> headers, Map<String, Object> authCfg,
                           SpecFile spec, Map<String, Object> scope) {
    }

    private final ApiRunReport report;

    public SpecExecutor(ApiRunReport report) {
        this.report = report;
    }

    // ------------------------------------------------------------------------------------

    public void run(ApiCase c) {
        String skip = c.skipReason();
        if (skip != null) {
            record(c, "", "", 0, 0, ApiRunReport.Outcome.SKIPPED, List.of(skip), "", "");
            throw new SkipException(skip);
        }
        SpecFile spec = c.spec();
        VariableResolver resolver = new VariableResolver(k -> ConfigReader.get(k, null), spec.source().getParent());
        ensureSpecVars(spec, resolver);

        Map<String, Object> scope = scopeOf(c);
        Prepared req;
        Map<String, Object> expect;
        Map<String, Object> extract;
        Map<String, Object> cleanup;
        Object authCfg;
        try {
            req = prepare(c, resolver, scope);
            expect = new LinkedHashMap<>(resolveMap(c.map("expect"), resolver, scope));
            extract = resolveMap(c.map("extract"), resolver, scope);
            cleanup = resolveMap(c.map("cleanup"), resolver, scope);
            authCfg = resolveAuthConfig(c, resolver, scope);
        } catch (VariableResolver.UnresolvedVariableException e) {
            String msg = explain(e);
            if (spec.isExtractedSomewhere(e.variable())) {
                String why = "Skipped - needs ${" + e.variable() + "} from an earlier request that did not provide it. " + msg;
                record(c, c.method(), c.path(), 0, 0, ApiRunReport.Outcome.SKIPPED, List.of(why), "", "");
                throw new SkipException(why);
            }
            record(c, c.method(), c.path(), 0, 0, ApiRunReport.Outcome.FAILED, List.of(msg), "", "");
            Assert.fail(c.displayName() + ": " + msg);
            return;
        } catch (IOException | RuntimeException e) {
            record(c, c.method(), c.path(), 0, 0, ApiRunReport.Outcome.FAILED, List.of("Bad spec: " + e.getMessage()), "", "");
            Assert.fail(c.displayName() + ": bad spec - " + e.getMessage());
            return;
        }

        applyDefaultsToExpect(c, expect);
        String requestText = describeRequest(req);
        List<String> failures = new ArrayList<>();
        ResponseData data = null;
        Response response = null;
        try {
            response = sendWithRetry(c, req, authCfg, resolver, scope);
            data = toData(response);
        } catch (RuntimeException e) {
            failures.add("request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (data != null) {
            failures.addAll(ExpectationChecker.check(expect, data));
            failures.addAll(checkSchema(c, expect, response, resolver));
            failures.addAll(extractVariables(extract, data, spec));
            if (!cleanup.isEmpty() && data.status() < 400) {
                registerCleanup(c, cleanup, authCfg, resolver);
            }
        }

        String responseText = data == null ? "" : describeResponse(data);
        ApiRunReport.Outcome outcome = failures.isEmpty() ? ApiRunReport.Outcome.PASSED : ApiRunReport.Outcome.FAILED;
        record(c, req.method, req.fullUrl(), data == null ? 0 : data.status(), data == null ? 0 : data.timeMs(),
            outcome, failures, requestText, responseText);

        if (!failures.isEmpty()) {
            String message = c.displayName() + " - " + failures.size() + " check(s) failed:\n - " + String.join("\n - ", failures)
                + "\n" + req.method + " " + req.fullUrl()
                + (data == null ? "" : "\nHTTP " + data.status() + " in " + data.timeMs() + " ms\n"
                + ApiRunReport.truncate(ApiRunReport.maskSecrets(data.body()), 1200));
            try {
                Allure.addAttachment("Failed checks", "text/plain", String.join("\n", failures));
            } catch (RuntimeException ignored) {
                // Allure is optional decoration - never let it hide the real failure
            }
            Assert.fail(message);
        }
    }

    /** Runs every cleanup request registered by any spec file, newest first. Never throws. */
    public int runCleanups(List<SpecFile> specs) {
        int failed = 0;
        for (SpecFile spec : specs) {
            @SuppressWarnings("unchecked")
            List<Cleanup> list = (List<Cleanup>) spec.runtime().get(CLEANUPS);
            if (list == null) {
                continue;
            }
            for (int i = list.size() - 1; i >= 0; i--) {
                Cleanup cl = list.get(i);
                try {
                    Prepared p = new Prepared();
                    p.method = cl.method();
                    p.path = cl.url();
                    p.base = "";
                    p.headers.putAll(cl.headers());
                    p.timeoutMs = 15000;
                    VariableResolver resolver = new VariableResolver(k -> ConfigReader.get(k, null), spec.source().getParent());
                    Response r = send(p, cl.authCfg(), spec, resolver, cl.scope());
                    log.info("[api-cleanup] {} -> HTTP {}", cl.description(), r.statusCode());
                    if (r.statusCode() >= 400 && r.statusCode() != 404) {
                        failed++;
                        log.warn("[api-cleanup] {} returned HTTP {} - data may be left behind", cl.description(), r.statusCode());
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("[api-cleanup] FAILED {}: {}", cl.description(), e.toString());
                }
            }
            list.clear();
        }
        return failed;
    }

    // ---- preparing ---------------------------------------------------------------------

    private Prepared prepare(ApiCase c, VariableResolver resolver, Map<String, Object> scope) throws IOException {
        SpecFile spec = c.spec();
        Map<String, Object> defaults = spec.map("defaults");
        boolean graphql = c.has("graphql");

        Prepared p = new Prepared();
        p.method = c.has("method") ? c.method() : (graphql ? "POST" : "GET");
        String path = resolver.resolveText(c.path().isEmpty() && graphql ? "/graphql" : c.path(), scope);
        p.path = path.replace("{", "%7B").replace("}", "%7D");
        p.base = c.has("baseUrl") && !WireMockManager.isEnabled()
            ? stripSlash(resolver.resolveText(String.valueOf(c.get("baseUrl")), scope))
            : baseUrl(spec, resolver, scope);

        // headers: spec-wide, then case-level (case wins)
        for (Map.Entry<String, Object> e : spec.map("headers").entrySet()) {
            p.headers.put(e.getKey(), String.valueOf(resolver.resolve(e.getValue(), scope)));
        }
        for (Map.Entry<String, Object> e : c.map("headers").entrySet()) {
            p.headers.put(e.getKey(), String.valueOf(resolver.resolve(e.getValue(), scope)));
        }
        if (c.has("soapAction")) {
            p.headers.put("SOAPAction", resolver.resolveText(String.valueOf(c.get("soapAction")), scope));
            p.contentType = "text/xml; charset=utf-8";
        }
        if (c.has("contentType")) {
            p.contentType = resolver.resolveText(String.valueOf(c.get("contentType")), scope);
        }

        for (Map.Entry<String, Object> e : c.map("query").entrySet()) {
            p.query.put(resolver.resolveText(e.getKey(), scope), resolver.resolve(e.getValue(), scope));
        }

        if (graphql) {
            Map<String, Object> gql = resolveMap(c.map("graphql"), resolver, scope);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", gql.get("query"));
            if (gql.get("variables") != null) {
                payload.put("variables", gql.get("variables"));
            }
            if (gql.get("operationName") != null) {
                payload.put("operationName", gql.get("operationName"));
            }
            p.bodyText = JSON.writeValueAsString(payload);
            p.contentType = "application/json";
        } else if (c.has("body")) {
            Object body = resolver.resolve(c.get("body"), scope);
            p.bodyText = body instanceof String s ? s : JSON.writeValueAsString(body);
            if (p.contentType == null) {
                p.contentType = "application/json";
            }
        } else if (c.has("bodyFile")) {
            String file = resolver.resolveText(String.valueOf(c.get("bodyFile")), scope);
            p.bodyText = resolver.resolveText(readSpecFile(spec, file), scope);
            if (p.contentType == null) {
                String t = p.bodyText.stripLeading();
                p.contentType = t.startsWith("<") ? "application/xml" : "application/json";
            }
        } else if (c.has("bodyText")) {
            p.bodyText = resolver.resolveText(String.valueOf(c.get("bodyText")), scope);
            if (p.contentType == null) {
                p.contentType = "text/plain";
            }
        } else if (c.has("form")) {
            p.form = resolveMap(c.map("form"), resolver, scope);
        } else if (c.has("multipart")) {
            p.multipart = resolveMap(c.map("multipart"), resolver, scope);
        }

        Object timeout = c.has("timeoutMs") ? c.get("timeoutMs") : defaults.getOrDefault("timeoutMs", ConfigReader.getInt("api.spec.timeoutMs", 30000));
        p.timeoutMs = timeout instanceof Number n ? n.intValue() : 30000;
        p.insecure = Boolean.parseBoolean(String.valueOf(defaults.getOrDefault("insecure", ConfigReader.get("api.spec.insecure", "false"))))
            || Boolean.TRUE.equals(c.get("insecure"));
        Object follow = c.has("followRedirects") ? c.get("followRedirects") : defaults.get("followRedirects");
        p.follow = follow == null || Boolean.parseBoolean(String.valueOf(follow));
        return p;
    }

    private String baseUrl(SpecFile spec, VariableResolver resolver, Map<String, Object> scope) {
        if (WireMockManager.isEnabled()) {
            return stripSlash(ApiConfig.baseUri());
        }
        String cli = ConfigReader.get("api.base.url", "").trim();
        if (!cli.isEmpty()) {
            return stripSlash(cli);
        }
        String fromSpec = spec.string("baseUrl");
        if (fromSpec != null && !fromSpec.isBlank()) {
            return stripSlash(resolver.resolveText(fromSpec, scope));
        }
        try {
            return stripSlash(ApiConfig.baseUri());
        } catch (RuntimeException e) {
            throw new IllegalStateException("No base URL. Add 'baseUrl:' to the spec, or pass -Dapi.base.url=https://your-host", e);
        }
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private Object resolveAuthConfig(ApiCase c, VariableResolver resolver, Map<String, Object> scope) {
        if (c.has("auth")) {
            return resolver.resolve(c.get("auth"), scope);
        }
        String cliToken = ConfigReader.get("api.auth.token", "").trim();
        if (!cliToken.isEmpty()) {
            Map<String, Object> bearer = new LinkedHashMap<>();
            bearer.put("type", "bearer");
            bearer.put("token", cliToken);
            return bearer;
        }
        Object specAuth = c.spec().settings().get("auth");
        return specAuth == null ? null : resolver.resolve(specAuth, scope);
    }

    private void ensureSpecVars(SpecFile spec, VariableResolver resolver) {
        synchronized (spec) {
            if (spec.runtime().containsKey(VARS_READY)) {
                return;
            }
            Map<String, Object> pending = new LinkedHashMap<>(spec.vars());
            for (int pass = 0; pass < 5 && !pending.isEmpty(); pass++) {
                for (Map.Entry<String, Object> e : new ArrayList<>(pending.entrySet())) {
                    try {
                        spec.vars().put(e.getKey(), resolver.resolve(e.getValue(), spec.vars()));
                        pending.remove(e.getKey());
                    } catch (VariableResolver.UnresolvedVariableException ex) {
                        // maybe defined by a later var - retry on the next pass
                    }
                }
            }
            // whatever still cannot resolve stays raw; using it in a request produces a clear error then
            spec.runtime().put(VARS_READY, Boolean.TRUE);
        }
    }

    private Map<String, Object> scopeOf(ApiCase c) {
        Map<String, Object> scope = c.scope();
        scope.putAll(c.spec().vars());      // extracted values since the case list was built
        scope.putAll(c.row());              // data row wins over shared variables
        scope.put("row", c.row());
        return scope;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(Map<String, Object> in, VariableResolver resolver, Map<String, Object> scope) {
        Object out = resolver.resolve(in, scope);
        return out instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static String explain(VariableResolver.UnresolvedVariableException e) {
        String v = e.variable();
        if (v.startsWith("env:")) {
            return "Environment variable " + v.substring(4) + " is not set (export " + v.substring(4) + "=...)";
        }
        if (v.startsWith("prop:")) {
            return "Config/system property " + v.substring(5) + " is not set (pass -D" + v.substring(5) + "=...)";
        }
        return "Variable ${" + v + "} is not defined - add it under 'vars:', extract it from an earlier response, "
            + "or pass -Dapi.var." + v + "=...";
    }

    private void applyDefaultsToExpect(ApiCase c, Map<String, Object> expect) {
        expect.putIfAbsent("status", "2xx");
        String cli = ConfigReader.get("api.spec.maxTimeMs", "").trim();
        Object fromDefaults = c.spec().map("defaults").get("maxTimeMs");
        if (!expect.containsKey("maxTimeMs")) {
            if (!cli.isEmpty()) {
                expect.put("maxTimeMs", Long.parseLong(cli));
            } else if (fromDefaults != null) {
                expect.put("maxTimeMs", fromDefaults);
            }
        }
        if (c.has("graphql") && !Boolean.TRUE.equals(c.get("allowGraphqlErrors"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = expect.get("body") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            body.putIfAbsent("errors", "absent");
            expect.put("body", body);
        }
    }

    // ---- sending -----------------------------------------------------------------------

    private Response sendWithRetry(ApiCase c, Prepared req, Object authCfg, VariableResolver resolver, Map<String, Object> scope) {
        Map<String, Object> retry = new LinkedHashMap<>(c.spec().map("defaults").get("retry") instanceof Map<?, ?> m
            ? castMap(m) : new LinkedHashMap<>());
        if (c.get("retry") instanceof Map<?, ?> own) {
            retry.putAll(castMap(own));
        } else if (c.get("retry") instanceof Number n) {
            retry.put("count", n);
        }
        int count = retry.get("count") instanceof Number n ? n.intValue() : ConfigReader.getInt("api.retry.count", 0);
        long delay = retry.get("delayMs") instanceof Number n ? n.longValue() : ApiConfig.retryBackoffMs();
        List<Integer> onStatus = new ArrayList<>(List.of(502, 503, 504));
        if (retry.get("onStatus") instanceof List<?> l) {
            onStatus.clear();
            l.forEach(x -> onStatus.add(Integer.parseInt(String.valueOf(x))));
        }
        RuntimeException last = null;
        Response response = null;
        for (int attempt = 0; attempt <= count; attempt++) {
            try {
                response = send(req, authCfg, c.spec(), resolver, scope);
                last = null;
                if (!onStatus.contains(response.statusCode()) || attempt == count) {
                    return response;
                }
                log.info("[api] {} -> HTTP {} - retry {}/{}", c.name(), response.statusCode(), attempt + 1, count);
            } catch (RuntimeException e) {
                last = e;
                log.info("[api] {} -> {} - retry {}/{}", c.name(), e.toString(), attempt + 1, count);
            }
            if (attempt < count) {
                sleep(delay * (1L << attempt));
            }
        }
        if (last != null) {
            throw last;
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.min(ms, 30_000));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Response send(Prepared p, Object authCfg, SpecFile spec, VariableResolver resolver, Map<String, Object> scope) {
        RequestSpecification rs = RestAssured.given();
        if (!p.path.startsWith("http") && !p.base.isEmpty()) {
            rs.baseUri(p.base);
        }
        if (p.insecure) {
            rs.relaxedHTTPSValidation();
        }
        rs.redirects().follow(p.follow);
        rs.config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", p.timeoutMs)
            .setParam("http.socket.timeout", p.timeoutMs)));

        p.headers.forEach((name, value) -> rs.header(name, value));
        for (Map.Entry<String, Object> q : p.query.entrySet()) {
            if (q.getValue() instanceof List<?> list) {
                rs.queryParam(q.getKey(), list.toArray());
            } else {
                rs.queryParam(q.getKey(), String.valueOf(q.getValue()));
            }
        }
        applyAuth(rs, authCfg, spec, resolver, scope);

        if (p.form != null) {
            rs.contentType("application/x-www-form-urlencoded");
            p.form.forEach((k, v) -> rs.formParam(k, String.valueOf(v)));
        } else if (p.multipart != null) {
            for (Map.Entry<String, Object> e : p.multipart.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.startsWith("file:")) {
                    rs.multiPart(e.getKey(), resolveFile(spec, v.substring(5)));
                } else {
                    rs.multiPart(e.getKey(), v);
                }
            }
        } else if (p.bodyText != null) {
            rs.contentType(p.contentType == null ? "application/json" : p.contentType);
            rs.body(p.bodyText);
        }
        return rs.request(Method.valueOf(p.method), p.path);
    }

    @SuppressWarnings("unchecked")
    private void applyAuth(RequestSpecification rs, Object authCfg, SpecFile spec, VariableResolver resolver, Map<String, Object> scope) {
        if (authCfg == null || Boolean.FALSE.equals(authCfg) || "none".equals(String.valueOf(authCfg))) {
            return;
        }
        if (!(authCfg instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("auth must be 'none' or an object with a 'type'");
        }
        Map<String, Object> auth = (Map<String, Object>) raw;
        String type = String.valueOf(auth.getOrDefault("type", "bearer")).toLowerCase(Locale.ROOT);
        switch (type) {
            case "none":
                return;
            case "bearer":
                applyBearer(rs, auth);
                return;
            case "basic":
                rs.auth().preemptive().basic(String.valueOf(auth.get("username")), String.valueOf(auth.get("password")));
                return;
            case "apikey":
                applyApiKey(rs, auth);
                return;
            case "login":
                rs.header(String.valueOf(auth.getOrDefault("header", "Authorization")),
                    String.valueOf(auth.getOrDefault("prefix", "Bearer ")) + loginToken(auth, spec, resolver, scope));
                return;
            case "config":
                RefreshingBearerAuthProvider.fromConfig().orElseThrow(
                    () -> new IllegalArgumentException("auth type 'config' needs api.auth.* keys (see docs/API_TESTING_GUIDE.md)"))
                    .apply(rs);
                return;
            default:
                throw new IllegalArgumentException("unknown auth type '" + type + "' (use none, bearer, basic, apiKey, login, config)");
        }
    }

    private static void applyBearer(RequestSpecification rs, Map<String, Object> auth) {
        Object token = auth.get("token");
        if (token == null || String.valueOf(token).isBlank()) {
            throw new IllegalArgumentException("auth type 'bearer' needs a 'token'");
        }
        rs.header(String.valueOf(auth.getOrDefault("header", "Authorization")),
            String.valueOf(auth.getOrDefault("prefix", "Bearer ")) + token);
    }

    private static void applyApiKey(RequestSpecification rs, Map<String, Object> auth) {
        String name = String.valueOf(auth.getOrDefault("name", "X-API-Key"));
        String value = String.valueOf(auth.get("value"));
        if ("query".equalsIgnoreCase(String.valueOf(auth.getOrDefault("in", "header")))) {
            rs.queryParam(name, value);
        } else {
            rs.header(name, value);
        }
    }

    /** Logs in once per spec file (any API: send credentials, read the token from the JSON reply). */
    @SuppressWarnings("unchecked")
    private String loginToken(Map<String, Object> auth, SpecFile spec, VariableResolver resolver, Map<String, Object> scope) {
        String cacheKey = "loginToken:" + auth.hashCode();
        synchronized (spec) {
            Object cached = spec.runtime().get(cacheKey);
            if (cached != null) {
                return String.valueOf(cached);
            }
            Map<String, Object> loginReq = auth.get("request") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
            if (loginReq.isEmpty()) {
                throw new IllegalArgumentException("auth type 'login' needs a 'request:' block (method, path, body) and a 'tokenPath:'");
            }
            Prepared p = new Prepared();
            p.method = String.valueOf(loginReq.getOrDefault("method", "POST")).toUpperCase(Locale.ROOT);
            p.path = String.valueOf(loginReq.get("path"));
            p.base = baseUrl(spec, resolver, scope);
            p.timeoutMs = 30000;
            if (loginReq.get("headers") instanceof Map<?, ?> h) {
                h.forEach((k, v) -> p.headers.put(String.valueOf(k), String.valueOf(v)));
            }
            try {
                if (loginReq.get("form") instanceof Map<?, ?> f) {
                    p.form = castMap(f);
                } else if (loginReq.get("body") != null) {
                    Object body = loginReq.get("body");
                    p.bodyText = body instanceof String s ? s : JSON.writeValueAsString(body);
                    p.contentType = "application/json";
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("login body is not serialisable: " + e.getMessage(), e);
            }
            Response r = send(p, null, spec, resolver, scope);
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("Login request failed: HTTP " + r.statusCode() + " - "
                    + ApiRunReport.truncate(ApiRunReport.maskSecrets(r.asString()), 300));
            }
            String tokenPath = String.valueOf(auth.getOrDefault("tokenPath", "token"));
            Object token;
            try {
                token = JsonPathLite.read(JsonPathLite.parse(r.asString()), tokenPath);
            } catch (IOException e) {
                throw new IllegalStateException("Login reply was not JSON: " + e.getMessage(), e);
            }
            if (token == JsonPathLite.MISSING || token == null) {
                throw new IllegalStateException("Login reply has no '" + tokenPath + "' field - set auth.tokenPath");
            }
            spec.runtime().put(cacheKey, String.valueOf(token));
            spec.vars().put("authToken", String.valueOf(token));
            return String.valueOf(token);
        }
    }

    private ResponseData toData(Response r) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header h : r.getHeaders().asList()) {
            headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        String body;
        try {
            body = r.asString();
        } catch (RuntimeException e) {
            body = "";
        }
        return new ResponseData(r.statusCode(), r.getTime(), headers, body);
    }

    // ---- checks beyond ExpectationChecker ------------------------------------------------

    private List<String> checkSchema(ApiCase c, Map<String, Object> expect, Response response, VariableResolver resolver) {
        List<String> failures = new ArrayList<>();
        Object ref = expect.get("schema");
        Object inline = expect.get("schemaInline");
        if (ref == null && inline == null) {
            return failures;
        }
        try {
            String schemaText = inline != null
                ? (inline instanceof String s ? s : JSON.writeValueAsString(inline))
                : readSpecFile(c.spec(), String.valueOf(ref));
            response.then().assertThat().body(JsonSchemaValidator.matchesJsonSchema(schemaText));
        } catch (AssertionError e) {
            String first = String.valueOf(e.getMessage()).strip().lines().limit(6).reduce((a, b) -> a + " | " + b).orElse("");
            failures.add("JSON schema mismatch: " + first);
        } catch (IOException | RuntimeException e) {
            failures.add("JSON schema could not be applied: " + e.getMessage());
        }
        return failures;
    }

    private List<String> extractVariables(Map<String, Object> extract, ResponseData data, SpecFile spec) {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Object> e : extract.entrySet()) {
            String expr = String.valueOf(e.getValue()).trim();
            try {
                Object value = extractOne(expr, data);
                if (value == JsonPathLite.MISSING || value == null) {
                    failures.add("extract " + e.getKey() + ": '" + expr + "' not found in the response");
                } else {
                    spec.vars().put(e.getKey(), value);
                }
            } catch (Exception ex) {
                failures.add("extract " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return failures;
    }

    private Object extractOne(String expr, ResponseData data) throws Exception {
        if (expr.equals("status")) {
            return data.status();
        }
        if (expr.equals("body")) {
            return data.body();
        }
        if (expr.equals("time")) {
            return data.timeMs();
        }
        if (expr.startsWith("header:")) {
            String h = data.headerJoined(expr.substring(7).trim());
            return h == null ? JsonPathLite.MISSING : h;
        }
        if (expr.startsWith("regex:")) {
            Matcher m = Pattern.compile(expr.substring(6)).matcher(data.body());
            return m.find() ? (m.groupCount() >= 1 ? m.group(1) : m.group()) : JsonPathLite.MISSING;
        }
        if (expr.startsWith("xml:")) {
            return ExpectationChecker.readXml(data.body(), expr.substring(4).trim());
        }
        Object json = data.json();
        if (json == null) {
            throw new IllegalStateException("response is not JSON");
        }
        return JsonPathLite.read(json, expr);
    }

    @SuppressWarnings("unchecked")
    private void registerCleanup(ApiCase c, Map<String, Object> cleanup, Object authCfg, VariableResolver resolver) {
        SpecFile spec = c.spec();
        Map<String, Object> scope = scopeOf(c);
        try {
            Map<String, Object> resolved = resolveMap(c.map("cleanup"), resolver, scope);
            Prepared p = new Prepared();
            p.method = String.valueOf(resolved.getOrDefault("method", "DELETE")).toUpperCase(Locale.ROOT);
            p.base = baseUrl(spec, resolver, scope);
            p.path = String.valueOf(resolved.get("path")).replace("{", "%7B").replace("}", "%7D");
            Map<String, String> headers = new LinkedHashMap<>();
            spec.map("headers").forEach((k, v) -> headers.put(k, String.valueOf(resolver.resolve(v, scope))));
            if (resolved.get("headers") instanceof Map<?, ?> h) {
                h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
            String url = p.path.startsWith("http") ? p.path : p.base + (p.path.startsWith("/") ? "" : "/") + p.path;
            synchronized (spec) {
                List<Cleanup> list = (List<Cleanup>) spec.runtime().computeIfAbsent(CLEANUPS, k -> new ArrayList<Cleanup>());
                list.add(new Cleanup(c.name() + " (" + p.method + " " + url + ")", p.method, url, headers, castAuth(authCfg), spec, scope));
            }
        } catch (RuntimeException e) {
            log.warn("[api-cleanup] could not register cleanup for {}: {}", c.name(), e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castAuth(Object authCfg) {
        return authCfg instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    // ---- files -------------------------------------------------------------------------

    private String readSpecFile(SpecFile spec, String relative) throws IOException {
        Path found = locate(spec, relative);
        if (found != null) {
            return Files.readString(found, StandardCharsets.UTF_8);
        }
        String cp = relative.startsWith("/") ? relative.substring(1) : relative;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cp)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("file not found: " + relative + " (looked next to the spec, in the project folder and on the classpath)");
    }

    private File resolveFile(SpecFile spec, String relative) {
        Path p = locate(spec, relative);
        if (p == null) {
            throw new IllegalArgumentException("upload file not found: " + relative);
        }
        return p.toFile();
    }

    private Path locate(SpecFile spec, String relative) {
        Path rel = Path.of(relative);
        List<Path> candidates = new ArrayList<>();
        if (rel.isAbsolute()) {
            candidates.add(rel);
        } else {
            if (spec.source().getParent() != null) {
                candidates.add(spec.source().getParent().resolve(rel));
            }
            candidates.add(Path.of("").toAbsolutePath().resolve(rel));
            candidates.add(Path.of("src/test/resources").resolve(rel));
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    // ---- reporting ---------------------------------------------------------------------

    private void record(ApiCase c, String method, String url, int status, long timeMs, ApiRunReport.Outcome outcome,
                        List<String> failures, String requestText, String responseText) {
        report.add(new ApiRunReport.Entry(c.spec().name(), c.name(), method, url, status, timeMs, outcome,
            new ArrayList<>(failures), requestText, responseText));
    }

    private String describeRequest(Prepared p) {
        StringBuilder sb = new StringBuilder(p.method).append(' ').append(p.fullUrl());
        if (!p.query.isEmpty()) {
            sb.append("\nquery: ").append(p.query);
        }
        p.headers.forEach((k, v) -> sb.append('\n').append(k).append(": ").append(ApiRunReport.isSecretHeader(k) ? "***" : v));
        if (p.bodyText != null) {
            sb.append("\n\n").append(ApiRunReport.truncate(ApiRunReport.maskSecrets(p.bodyText), 2000));
        } else if (p.form != null) {
            sb.append("\n\nform: ").append(ApiRunReport.maskSecrets(String.valueOf(p.form)));
        } else if (p.multipart != null) {
            sb.append("\n\nmultipart: ").append(p.multipart.keySet());
        }
        return sb.toString();
    }

    private String describeResponse(ResponseData d) {
        StringBuilder sb = new StringBuilder("HTTP ").append(d.status()).append(" - ").append(d.timeMs()).append(" ms");
        d.headers().forEach((k, v) -> {
            if (k != null && !ApiRunReport.isSecretHeader(k)) {
                sb.append('\n').append(k).append(": ").append(String.join(", ", v));
            }
        });
        sb.append("\n\n").append(ApiRunReport.truncate(ApiRunReport.maskSecrets(d.body()), 3000));
        return sb.toString();
    }
}
