package com.automation.core.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.common.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-logic check of the WireMock fake that stands in for DemoQA's Account/BookStore API — no
 * browser, no network beyond localhost. Runs in {@code mvn verify -Punit-tests}.
 *
 * <p>These assertions mirror what BookStoreApiTest / BookStoreApiNegativeTest expect of the real
 * API; if one of those test classes changes what it asserts, this is the place to keep the fake
 * in step.
 */
class DemoQaBookStoreFakeTest {

    private static final String PASSWORD = "Password123!@";
    private static final String GIT_POCKET_GUIDE = "9781449325862";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String base;

    @BeforeAll
    static void startServer() {
        base = WireMockManager.start();
    }

    @AfterAll
    static void stopServer() {
        WireMockManager.stop();
    }

    @Test
    void accountLifecycle_createTokenAddReadDelete() throws Exception {
        String name = uniqueName();
        String creds = Json.write(Map.of("userName", name, "password", PASSWORD));

        HttpResponse<String> created = call("POST", "/Account/v1/User", creds, null);
        assertEquals(201, created.statusCode());
        String userId = (String) json(created).get("userID");
        assertNotNull(userId);

        String token = (String) json(call("POST", "/Account/v1/GenerateToken", creds, null)).get("token");
        assertNotNull(token);

        String add = Json.write(Map.of("userId", userId,
            "collectionOfIsbns", List.of(Map.of("isbn", GIT_POCKET_GUIDE))));
        assertEquals(201, call("POST", "/BookStore/v1/Books", add, token).statusCode());

        HttpResponse<String> detail = call("GET", "/Account/v1/User/" + userId, null, token);
        assertEquals(200, detail.statusCode());
        assertEquals(userId, json(detail).get("userId"));
        assertTrue(detail.body().contains(GIT_POCKET_GUIDE));

        assertEquals(204, call("DELETE", "/BookStore/v1/Book",
            Json.write(Map.of("isbn", GIT_POCKET_GUIDE, "userId", userId)), token).statusCode());
        assertFalse(call("GET", "/Account/v1/User/" + userId, null, token).body().contains(GIT_POCKET_GUIDE));

        assertEquals(204, call("DELETE", "/Account/v1/User/" + userId, null, token).statusCode());
        assertEquals(404, call("POST", "/Account/v1/Login", creds, null).statusCode(),
            "a deleted account can no longer log in");
    }

    @Test
    void duplicateAccount_returns406WithUserExistsMessage() throws Exception {
        String creds = Json.write(Map.of("userName", uniqueName(), "password", PASSWORD));
        assertEquals(201, call("POST", "/Account/v1/User", creds, null).statusCode());

        HttpResponse<String> second = call("POST", "/Account/v1/User", creds, null);

        assertEquals(406, second.statusCode());
        assertEquals("1204", json(second).get("code"));
        assertEquals("User exists!", json(second).get("message"));
    }

    @Test
    void wrongPassword_yieldsFailedStatusAndExplicitNullToken() throws Exception {
        String name = uniqueName();
        call("POST", "/Account/v1/User", Json.write(Map.of("userName", name, "password", PASSWORD)), null);

        HttpResponse<String> response = call("POST", "/Account/v1/GenerateToken",
            Json.write(Map.of("userName", name, "password", "Wrong!123")), null);

        assertEquals(200, response.statusCode(), "the live API signals failure in the body, not the status");
        assertEquals("Failed", json(response).get("status"));
        assertTrue(response.body().contains("\"token\":null"), "explicit null, as the live API sends it");
    }

    @Test
    void usersEndpoint_requiresATokenThatBelongsToThatUser() throws Exception {
        String credsA = Json.write(Map.of("userName", uniqueName(), "password", PASSWORD));
        String credsB = Json.write(Map.of("userName", uniqueName(), "password", PASSWORD));
        call("POST", "/Account/v1/User", credsA, null);
        String idB = (String) json(call("POST", "/Account/v1/User", credsB, null)).get("userID");
        String tokenA = (String) json(call("POST", "/Account/v1/GenerateToken", credsA, null)).get("token");

        assertEquals(401, call("GET", "/Account/v1/User/" + idB, null, null).statusCode(), "no token");
        HttpResponse<String> crossUser = call("GET", "/Account/v1/User/" + idB, null, tokenA);
        assertEquals(401, crossUser.statusCode(), "another user's token");
        assertEquals("1200", json(crossUser).get("code"));
    }

    @Test
    void books_unknownIsbnIsRejected_duplicateAddIsRejected() throws Exception {
        assertEquals(400, call("GET", "/BookStore/v1/Book?ISBN=0000000000000", null, null).statusCode());

        String creds = Json.write(Map.of("userName", uniqueName(), "password", PASSWORD));
        String userId = (String) json(call("POST", "/Account/v1/User", creds, null)).get("userID");
        String token = (String) json(call("POST", "/Account/v1/GenerateToken", creds, null)).get("token");
        String add = Json.write(Map.of("userId", userId,
            "collectionOfIsbns", List.of(Map.of("isbn", GIT_POCKET_GUIDE))));

        assertEquals(201, call("POST", "/BookStore/v1/Books", add, token).statusCode());
        HttpResponse<String> again = call("POST", "/BookStore/v1/Books", add, token);
        assertEquals(400, again.statusCode());
        assertEquals("1215", json(again).get("code"));
    }

    @Test
    void catalogue_isNonEmpty() throws Exception {
        // Not "== 8": once someone records the real catalogue into src/test/resources/wiremock/,
        // that recording (correctly) takes priority over the fake's seed data.
        HttpResponse<String> response = call("GET", "/BookStore/v1/Books", null, null);
        assertEquals(200, response.statusCode());
        assertFalse(((List<?>) json(response).get("books")).isEmpty());
    }

    // ------------------------------------------------------------------

    private static String uniqueName() {
        return "fake_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static HttpResponse<String> call(String method, String path, String body, String token)
        throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<?, ?> json(HttpResponse<String> response) {
        return Json.read(response.body(), Map.class);
    }
}
