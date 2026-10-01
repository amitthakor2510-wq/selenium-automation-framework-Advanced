package com.automation.sites.demoqa.api;

import com.automation.core.api.ApiConfig;
import com.automation.core.exceptions.FrameworkException;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DemoQA account + book-collection calls for <b>test data setup and teardown</b> — creating a
 * user in milliseconds over REST so a UI test doesn't have to click through registration (which is
 * also the step DemoQA guards with a rate-limited reCAPTCHA), then deleting that user afterwards.
 *
 * <p>Not an assertion helper: the functional API checks live in {@code BookStoreApiTest}. A call
 * here that doesn't return what setup needs throws immediately, with the response body in the
 * message, so a broken precondition shows up as a setup failure rather than a confusing UI one.
 *
 * <p>Every request carries its own base URI instead of using RestAssured's global
 * {@code baseURI}, so this class never depends on (or disturbs) {@code ApiClient.configure()}
 * state and is safe from any test thread. Pick the target explicitly:
 * <ul>
 *   <li>{@link #real()} — the site's real URL. Use it for UI tests: the browser talks to the
 *       real site, so the data must be created there, even when {@code -Dmock.enabled=true}.</li>
 *   <li>{@link #current()} — whatever {@code ApiConfig.baseUri()} resolves to (the mock server
 *       when mocking is on). Use it from API tests.</li>
 * </ul>
 */
public final class DemoQaAccountApi {

    private static final Logger logger = LoggerFactory.getLogger(DemoQaAccountApi.class);

    /** Password used for generated accounts — meets DemoQA's complexity rule. */
    public static final String DEFAULT_PASSWORD = "Password123!@";

    /** A user that exists on the server, with a live bearer token for authenticated calls. */
    public record ApiUser(String userId, String username, String password, String token) {
    }

    private final String baseUri;

    private DemoQaAccountApi(String baseUri) {
        this.baseUri = baseUri;
    }

    /** Targets the site's real URL (the "url" config key), never the mock server. */
    public static DemoQaAccountApi real() {
        return new DemoQaAccountApi(ApiConfig.realBaseUri());
    }

    /** Targets {@code ApiConfig.baseUri()} — the mock server when {@code mock.enabled=true}. */
    public static DemoQaAccountApi current() {
        return new DemoQaAccountApi(ApiConfig.baseUri());
    }

    // ------------------------------------------------------------------ create

    /** Creates an account with a random username and {@link #DEFAULT_PASSWORD}, and gets its token. */
    public ApiUser createUniqueUser() {
        return createUser("ApiSeed_" + UUID.randomUUID().toString().substring(0, 8), DEFAULT_PASSWORD);
    }

    public ApiUser createUser(String username, String password) {
        Response created = spec()
            .body(Map.of("userName", username, "password", password))
            .when().post("/Account/v1/User");
        requireStatus(created, 201, "create account " + username);
        String userId = created.jsonPath().getString("userID");

        Response tokenResponse = spec()
            .body(Map.of("userName", username, "password", password))
            .when().post("/Account/v1/GenerateToken");
        requireStatus(tokenResponse, 200, "generate token for " + username);
        String token = tokenResponse.jsonPath().getString("token");
        if (token == null || token.isBlank()) {
            throw new FrameworkException("No token returned for " + username + ": " + tokenResponse.asString());
        }
        logger.info("[api-setup] created account {} ({})", username, userId);
        return new ApiUser(userId, username, password, token);
    }

    // ------------------------------------------------------------------ books

    /** ISBNs of every book in the store catalogue, in the order the API returns them. */
    public List<String> catalogueIsbns() {
        Response response = spec().when().get("/BookStore/v1/Books");
        requireStatus(response, 200, "list catalogue");
        return response.jsonPath().getList("books.isbn", String.class);
    }

    /** Title of the catalogue book with this ISBN — what the UI will display for it. */
    public String bookTitle(String isbn) {
        Response response = spec().queryParam("ISBN", isbn).when().get("/BookStore/v1/Book");
        requireStatus(response, 200, "get book " + isbn);
        return response.jsonPath().getString("title");
    }

    public void addBook(ApiUser user, String isbn) {
        Response response = authorized(user)
            .body(Map.of("userId", user.userId(), "collectionOfIsbns", List.of(Map.of("isbn", isbn))))
            .when().post("/BookStore/v1/Books");
        requireStatus(response, 201, "add book " + isbn + " to " + user.username());
    }

    /** ISBNs currently in the user's collection — for verifying what a UI action really did. */
    public List<String> collectionIsbns(ApiUser user) {
        Response response = authorized(user).when().get("/Account/v1/User/" + user.userId());
        requireStatus(response, 200, "read collection of " + user.username());
        return response.jsonPath().getList("books.isbn", String.class);
    }

    /**
     * Polls until the book is no longer in the collection. DemoQA's DELETE and a following GET are
     * not guaranteed consistent on the very first read, so a single immediate check is flaky.
     */
    public boolean awaitBookRemoved(ApiUser user, String isbn, int attempts, long pauseMs) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!collectionIsbns(user).contains(isbn)) {
                return true;
            }
            if (attempt < attempts) {
                pause(pauseMs);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ teardown

    /** Deletes the account (its books go with it). Throws if the server refuses. */
    public void deleteUser(ApiUser user) {
        Response response = authorized(user).when().delete("/Account/v1/User/" + user.userId());
        requireStatus(response, 204, "delete account " + user.username());
        logger.info("[api-teardown] deleted account {} ({})", user.username(), user.userId());
    }

    /**
     * Deletes an account you only know the credentials of — e.g. one a UI test registered by
     * clicking through the form. Logs in to get the ID and a token, then deletes.
     *
     * <p>Never throws for "nothing to delete": if the login is rejected the account doesn't
     * exist (registration never happened or already cleaned up), which is a fine end state.
     *
     * @return true if an account was found and deleted
     */
    public boolean deleteUserByCredentials(String username, String password) {
        Response login = spec()
            .body(Map.of("userName", username, "password", password))
            .when().post("/Account/v1/Login");
        if (login.statusCode() != 200) {
            logger.info("[api-teardown] no account to delete for {} (login HTTP {})", username, login.statusCode());
            return false;
        }
        String userId = login.jsonPath().getString("userId");
        String token = login.jsonPath().getString("token");
        if (userId == null || token == null) {
            logger.warn("[api-teardown] login for {} returned no userId/token: {}", username, login.asString());
            return false;
        }
        deleteUser(new ApiUser(userId, username, password, token));
        return true;
    }

    // ------------------------------------------------------------------ internals

    private RequestSpecification spec() {
        return RestAssured.given().baseUri(baseUri).contentType(ContentType.JSON);
    }

    private RequestSpecification authorized(ApiUser user) {
        return spec().header("Authorization", "Bearer " + user.token());
    }

    private static void requireStatus(Response response, int expected, String what) {
        if (response.statusCode() != expected) {
            throw new FrameworkException("API setup step failed — " + what + ": expected HTTP " + expected
                + " but got " + response.statusCode() + " — " + response.asString());
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FrameworkException("Interrupted while waiting for the API to settle", e);
        }
    }
}
