package com.automation.core.api.auth;

import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.specification.RequestSpecification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bearer-token {@link AuthProvider} that fetches its own token and fetches a new one
 * shortly before the old one expires — for OAuth 2.0 and for JWT-from-a-login-endpoint APIs.
 *
 * Token requests use java.net.http (not RestAssured) on purpose: RestAssured's global
 * AllureRestAssured filter would attach the token request, client secret included, to
 * the Allure report.
 *
 * Ways to build one:
 *  - {@link #oauth2ClientCredentials}  (machine-to-machine)
 *  - {@link #oauth2Password}           (resource-owner password grant; uses refresh_token when issued)
 *  - {@link #jwtLogin}                 (POST credentials to a login endpoint, read the JWT from the reply)
 *  - {@link #fromConfig()}             (same, driven by api.auth.* keys — see GenericApiSecurityChecks)
 *
 * Usage: {@code ApiClient.authenticatedRequest(provider).when().get("/orders")}
 */
public final class RefreshingBearerAuthProvider implements AuthProvider {

    /** A token plus the moment it stops being valid. */
    public record IssuedToken(String accessToken, Instant expiresAt) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).build();
    private static final long DEFAULT_SKEW_SECONDS = 30;
    private static final long DEFAULT_LIFETIME_SECONDS = 300;

    private final Supplier<IssuedToken> issuer;
    private final long skewSeconds;
    private IssuedToken current; // guarded by 'this'

    public RefreshingBearerAuthProvider(Supplier<IssuedToken> issuer, long skewSeconds) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.skewSeconds = skewSeconds;
    }

    /** The current access token, fetching a new one first if none exists or it expires within the skew window. */
    public synchronized String currentToken() {
        if (current == null || Instant.now().plusSeconds(skewSeconds).isAfter(current.expiresAt())) {
            current = Objects.requireNonNull(issuer.get(), "token issuer returned null");
        }
        return current.accessToken();
    }

    /** Drops the cached token so the next request fetches a new one (e.g. after an unexpected 401). */
    public synchronized void forceRefresh() {
        current = null;
    }

    @Override
    public RequestSpecification apply(RequestSpecification request) {
        return request.header("Authorization", "Bearer " + currentToken());
    }

    // ------------------------------------------------------------------ factories

    public static RefreshingBearerAuthProvider oauth2ClientCredentials(
            String tokenUrl, String clientId, String clientSecret, String scope) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        putIfPresent(form, "scope", scope);
        return new RefreshingBearerAuthProvider(new OAuthIssuer(tokenUrl, form), DEFAULT_SKEW_SECONDS);
    }

    /** clientId / clientSecret / scope may be null or blank if the server doesn't need them. */
    public static RefreshingBearerAuthProvider oauth2Password(
            String tokenUrl, String clientId, String clientSecret,
            String username, String password, String scope) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("username", username);
        form.put("password", password);
        putIfPresent(form, "client_id", clientId);
        putIfPresent(form, "client_secret", clientSecret);
        putIfPresent(form, "scope", scope);
        return new RefreshingBearerAuthProvider(new OAuthIssuer(tokenUrl, form), DEFAULT_SKEW_SECONDS);
    }

    /**
     * POSTs {@code jsonBody} to {@code loginUrl} and reads the token from {@code tokenField}
     * (dotted path allowed, e.g. "data.accessToken"). Expiry comes from expires_in if present,
     * else the JWT's own exp claim, else 5 minutes.
     */
    public static RefreshingBearerAuthProvider jwtLogin(
            String loginUrl, Map<String, Object> jsonBody, String tokenField) {
        Supplier<IssuedToken> issuer = () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(loginUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(jsonBody)))
                    .build();
                return toToken(send(request), tokenField);
            } catch (IOException e) {
                throw new IllegalStateException("Login request to " + loginUrl + " failed: " + e.getMessage(), e);
            }
        };
        return new RefreshingBearerAuthProvider(issuer, DEFAULT_SKEW_SECONDS);
    }

    /**
     * Builds a provider from api.auth.* config keys, or empty if api.auth.type is unset.
     * Secrets can be passed on the command line (-Dapi.auth.clientSecret=...) so they never land in a file.
     *
     *   api.auth.type = bearer | oauth2-client-credentials | oauth2-password | jwt-login | none
     *   bearer:                token
     *   oauth2-client-credentials: tokenUrl, clientId, clientSecret, [scope]
     *   oauth2-password:       tokenUrl, username, password, [clientId, clientSecret, scope]
     *   jwt-login:             loginUrl, username, password, [usernameField, passwordField, tokenField]
     * tokenUrl / loginUrl may be relative ("/auth/login") — resolved against the site's url.
     */
    public static Optional<RefreshingBearerAuthProvider> fromConfig() {
        String type = ConfigReader.get("api.auth.type", "").trim().toLowerCase();
        return switch (type) {
            case "", "none" -> Optional.empty();
            case "bearer" -> {
                String token = ConfigReader.get("api.auth.token");
                Instant expiry = jwtExpiryEpochSeconds(token).isPresent()
                    ? Instant.ofEpochSecond(jwtExpiryEpochSeconds(token).getAsLong())
                    : Instant.now().plus(Duration.ofDays(3650));
                yield Optional.of(new RefreshingBearerAuthProvider(() -> new IssuedToken(token, expiry), 0));
            }
            case "oauth2-client-credentials" -> Optional.of(oauth2ClientCredentials(
                resolveUrl(ConfigReader.get("api.auth.tokenUrl")),
                ConfigReader.get("api.auth.clientId"),
                ConfigReader.get("api.auth.clientSecret"),
                ConfigReader.get("api.auth.scope", "")));
            case "oauth2-password" -> Optional.of(oauth2Password(
                resolveUrl(ConfigReader.get("api.auth.tokenUrl")),
                ConfigReader.get("api.auth.clientId", ""),
                ConfigReader.get("api.auth.clientSecret", ""),
                ConfigReader.get("api.auth.username"),
                ConfigReader.get("api.auth.password"),
                ConfigReader.get("api.auth.scope", "")));
            case "jwt-login" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(ConfigReader.get("api.auth.usernameField", "username"), ConfigReader.get("api.auth.username"));
                body.put(ConfigReader.get("api.auth.passwordField", "password"), ConfigReader.get("api.auth.password"));
                yield Optional.of(jwtLogin(resolveUrl(ConfigReader.get("api.auth.loginUrl")), body,
                    ConfigReader.get("api.auth.tokenField", "token")));
            }
            default -> throw new IllegalStateException("Unknown api.auth.type '" + type
                + "'. Use bearer, oauth2-client-credentials, oauth2-password, jwt-login or none.");
        };
    }

    /** Reads the exp claim (epoch seconds) from a JWT without verifying it; empty if not a JWT or no exp. */
    public static OptionalLong jwtExpiryEpochSeconds(String jwt) {
        if (jwt == null) {
            return OptionalLong.empty();
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return OptionalLong.empty();
        }
        try {
            JsonNode exp = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])).path("exp");
            return exp.isNumber() ? OptionalLong.of(exp.asLong()) : OptionalLong.empty();
        } catch (IllegalArgumentException | IOException e) {
            return OptionalLong.empty();
        }
    }

    // ------------------------------------------------------------------ internals

    /** OAuth issuer: remembers refresh_token if the server hands one out and prefers it on the next refresh. */
    private static final class OAuthIssuer implements Supplier<IssuedToken> {
        private final String tokenUrl;
        private final Map<String, String> initialForm;
        private String refreshToken;

        OAuthIssuer(String tokenUrl, Map<String, String> initialForm) {
            this.tokenUrl = tokenUrl;
            this.initialForm = initialForm;
        }

        @Override
        public IssuedToken get() {
            if (refreshToken != null) {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", refreshToken);
                putIfPresent(form, "client_id", initialForm.get("client_id"));
                putIfPresent(form, "client_secret", initialForm.get("client_secret"));
                try {
                    return accept(send(formRequest(tokenUrl, form)));
                } catch (IllegalStateException refreshFailed) {
                    refreshToken = null; // fall through to a full re-authentication
                }
            }
            return accept(send(formRequest(tokenUrl, initialForm)));
        }

        private IssuedToken accept(JsonNode body) {
            JsonNode rt = body.path("refresh_token");
            refreshToken = rt.isTextual() && !rt.asText().isBlank() ? rt.asText() : null;
            return toToken(body, "access_token");
        }
    }

    private static HttpRequest formRequest(String url, Map<String, String> form) {
        String encoded = form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build();
    }

    private static JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Token request to " + request.uri() + " failed: HTTP "
                    + response.statusCode() + " - " + abbreviate(response.body()));
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Token request to " + request.uri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting a token from " + request.uri(), e);
        }
    }

    private static IssuedToken toToken(JsonNode body, String tokenField) {
        JsonNode node = body;
        for (String segment : tokenField.split("\\.")) {
            node = node.path(segment);
        }
        if (node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            StringBuilder names = new StringBuilder();
            body.fieldNames().forEachRemaining(n -> names.append(names.length() == 0 ? "" : ", ").append(n));
            throw new IllegalStateException("Token response has no '" + tokenField
                + "' field. Fields present: [" + names + "]");
        }
        String token = node.asText();
        JsonNode expiresIn = body.path("expires_in");
        Instant expiry;
        if (expiresIn.isNumber() || (expiresIn.isTextual() && expiresIn.asText().matches("\\d+"))) {
            expiry = Instant.now().plusSeconds(expiresIn.asLong());
        } else {
            OptionalLong jwtExp = jwtExpiryEpochSeconds(token);
            expiry = jwtExp.isPresent()
                ? Instant.ofEpochSecond(jwtExp.getAsLong())
                : Instant.now().plusSeconds(DEFAULT_LIFETIME_SECONDS);
        }
        return new IssuedToken(token, expiry);
    }

    private static String resolveUrl(String url) {
        return url.startsWith("/") ? ConfigReader.get("url").replaceAll("/+$", "") + url : url;
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }
}
