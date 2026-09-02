package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.api.ApiClient;
import com.automation.sites.core.BaseApiTest;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Error-path coverage for the same BookStore/Account API {@link BookStoreApiTest} covers on the
 * happy path. Deliberately a separate class rather than more {@code @Test} methods bolted onto
 * {@code BookStoreApiTest}: that class's 9 tests are one long {@code dependsOnMethods} chain
 * sharing a single account's state end-to-end, and interleaving failure-path calls into that
 * chain would either risk leaving the shared account in a state a later happy-path step doesn't
 * expect, or force every negative test into the same fragile chain position for no real benefit.
 * This class creates its own disposable account instead and never touches {@code BookStoreApiTest}'s.
 *
 * <p>Each test below documents a specific way the API is known to respond to bad input — a
 * duplicate account, a wrong password, an ISBN that doesn't exist, a request with no auth token
 * — based on the well-documented public DemoQA Book Store API contract. As with the JSON schemas
 * in {@code src/test/resources/schemas/bookstore/}, these exact status codes/messages were not
 * re-verified against a fresh live response as part of writing this class (no live network
 * access in that pass) — run this class for real once before relying on it in CI:
 *
 * <pre>{@code mvn test -Dtest=BookStoreApiNegativeTest}</pre>
 *
 * If a specific assertion is off, the failure will name exactly which one, same as the schema
 * validation failures in {@code BookStoreApiTest} do.
 */
public class BookStoreApiNegativeTest extends BaseApiTest {

    private static final Logger logger = LoggerFactory.getLogger(BookStoreApiNegativeTest.class);

    private static final String UNIQUE_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String API_USERNAME = "ApiNegTest_" + UNIQUE_ID;
    private static final String API_PASSWORD = "Password123!@";
    private static final String WRONG_PASSWORD = "WrongPassword!123";

    // A real ISBN from the catalogue — needed for the "ISBN already in collection" case below,
    // which specifically requires an ISBN that DOES exist. Everything else in this class only
    // needs ISBNs that don't exist, which can just be made up.
    private static final String KNOWN_GOOD_ISBN = "9781449325862"; // "Git Pocket Guide"
    private static final String NONEXISTENT_ISBN = "0000000000000";

    // Captured once at account creation (Test 1) and reused by every later test in this class —
    // same sharing pattern BookStoreApiTest itself uses for its own single account.
    private static String userId;
    private static String validToken;

    @BeforeClass(alwaysRun = true)
    public void logTestStart() {
        logger.info("=== Book Store API Negative Test Started — user: " + API_USERNAME + " ===");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Account creation
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 1, groups = {"regression", "api"},
        description = "API (negative) - Creating the same account twice is rejected")
    public void createDuplicateAccount_ShouldReturn406() {
        Map<String, String> body = Map.of("userName", API_USERNAME, "password", API_PASSWORD);

        // First creation succeeds — this is the account every other test in this class reuses.
        Response created = ApiClient.jsonRequest().body(body).when().post("/Account/v1/User")
            .then().statusCode(201).extract().response();
        userId = created.jsonPath().getString("userID");

        // Second creation with the exact same credentials should be rejected.
        ApiClient.jsonRequest().body(body).when().post("/Account/v1/User")
            .then()
            .statusCode(406)
            .body("code", equalTo("1204"))
            .body("message", equalTo("User exists!"));

        logger.info("✓ createDuplicateAccount_ShouldReturn406 PASS — userId: " + userId);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Authentication
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 2, groups = {"regression", "api"},
        description = "API (negative) - Wrong password does not yield a token",
        dependsOnMethods = "createDuplicateAccount_ShouldReturn406")
    public void generateToken_WithWrongPassword_ShouldReturnFailedStatus() {
        // GenerateToken deliberately still returns 200 here, not a 4xx — the API's own contract
        // signals failure via the body's "status"/"token" fields, not the HTTP status code.
        // Asserting statusCode(200) is intentional, not a mistake.
        ApiClient.jsonRequest()
            .body(Map.of("userName", API_USERNAME, "password", WRONG_PASSWORD))
            .when()
            .post("/Account/v1/GenerateToken")
            .then()
            .statusCode(200)
            .body("token", nullValue())
            .body("status", equalTo("Failed"));

        logger.info("✓ generateToken_WithWrongPassword_ShouldReturnFailedStatus PASS");
    }

    @Test(priority = 3, groups = {"regression", "api"},
        description = "API (negative) - Authorized endpoint reports false for wrong password",
        dependsOnMethods = "createDuplicateAccount_ShouldReturn406")
    public void isAuthorized_WithWrongPassword_ShouldReturnFalse() {
        ApiClient.jsonRequest()
            .body(Map.of("userName", API_USERNAME, "password", WRONG_PASSWORD))
            .when()
            .post("/Account/v1/Authorized")
            .then()
            .statusCode(200)
            .body(equalTo("false"));

        logger.info("✓ isAuthorized_WithWrongPassword_ShouldReturnFalse PASS");
    }

    @Test(priority = 4, groups = {"regression", "api"},
        description = "API (negative) setup - Generate a valid token, needed by the add/delete/user-detail tests below",
        dependsOnMethods = "createDuplicateAccount_ShouldReturn406")
    public void generateValidToken_ForSubsequentTests() {
        Response response = ApiClient.jsonRequest()
            .body(Map.of("userName", API_USERNAME, "password", API_PASSWORD))
            .when()
            .post("/Account/v1/GenerateToken")
            .then()
            .statusCode(200)
            .body("status", equalTo("Success"))
            .extract().response();

        validToken = response.jsonPath().getString("token");
        logger.info("✓ generateValidToken_ForSubsequentTests PASS");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Book lookups
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 5, groups = {"regression", "api"},
        description = "API (negative) - Fetching a book by a nonexistent ISBN returns 400")
    public void getBookByIsbn_WithInvalidIsbn_ShouldReturn400() {
        ApiClient.request()
            .queryParam("ISBN", NONEXISTENT_ISBN)
            .when()
            .get("/BookStore/v1/Book")
            .then()
            .statusCode(400)
            .body("code", equalTo("1205"))
            .body("message", equalTo("ISBN supplied is not available in the Books Collection!"));

        logger.info("✓ getBookByIsbn_WithInvalidIsbn_ShouldReturn400 PASS");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Adding/removing books — needs the valid token from Test 4 above
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 6, groups = {"regression", "api"},
        description = "API (negative) - Adding a nonexistent ISBN to the collection returns 400",
        dependsOnMethods = "generateValidToken_ForSubsequentTests")
    public void addBookToCollection_WithInvalidIsbn_ShouldReturn400() {
        ApiClient.authorizedRequest(validToken)
            .body(Map.of("userId", userId, "collectionOfIsbns",
                List.of(Map.of("isbn", NONEXISTENT_ISBN))))
            .when()
            .post("/BookStore/v1/Books")
            .then()
            .statusCode(400)
            .body("code", equalTo("1205"))
            .body("message", equalTo("ISBN supplied is not available in the Books Collection!"));

        logger.info("✓ addBookToCollection_WithInvalidIsbn_ShouldReturn400 PASS");
    }

    @Test(priority = 7, groups = {"regression", "api"},
        description = "API (negative) - Adding an ISBN already in the collection returns 400",
        dependsOnMethods = "addBookToCollection_WithInvalidIsbn_ShouldReturn400")
    public void addBookToCollection_DuplicateIsbn_ShouldReturn400() {
        Map<String, Object> addBody = Map.of("userId", userId, "collectionOfIsbns",
            List.of(Map.of("isbn", KNOWN_GOOD_ISBN)));

        // First add succeeds — establishes the "already present" state for the assertion below.
        ApiClient.authorizedRequest(validToken).body(addBody).when().post("/BookStore/v1/Books")
            .then().statusCode(201);

        // Adding the exact same ISBN again should be rejected.
        ApiClient.authorizedRequest(validToken).body(addBody).when().post("/BookStore/v1/Books")
            .then()
            .statusCode(400)
            .body("code", equalTo("1215"))
            .body("message", equalTo("ISBN already present in the User's Collection!"));

        logger.info("✓ addBookToCollection_DuplicateIsbn_ShouldReturn400 PASS");
    }

    @Test(priority = 8, groups = {"regression", "api"},
        description = "API (negative) - Deleting an ISBN that isn't in the collection returns 400",
        dependsOnMethods = "addBookToCollection_DuplicateIsbn_ShouldReturn400")
    public void deleteBookFromCollection_NotInCollection_ShouldReturn400() {
        ApiClient.authorizedRequest(validToken)
            .body(Map.of("isbn", NONEXISTENT_ISBN, "userId", userId))
            .when()
            .delete("/BookStore/v1/Book")
            .then()
            .statusCode(400)
            .body("code", equalTo("1206"))
            .body("message", equalTo("ISBN supplied is not available in User's Collection!"));

        logger.info("✓ deleteBookFromCollection_NotInCollection_ShouldReturn400 PASS");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Missing/invalid auth token
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 9, groups = {"regression", "api"},
        description = "API (negative) - Fetching user details with no Authorization header returns 401",
        dependsOnMethods = "createDuplicateAccount_ShouldReturn406")
    public void getUserDetails_WithoutAuthToken_ShouldReturn401() {
        ApiClient.request()
            .when()
            .get("/Account/v1/User/" + userId)
            .then()
            .statusCode(401)
            .body("code", equalTo("1200"))
            .body("message", equalTo("User not authorized!"));

        logger.info("✓ getUserDetails_WithoutAuthToken_ShouldReturn401 PASS");
    }

    @Test(priority = 10, groups = {"regression", "api"},
        description = "API (negative) - Deleting the account with no Authorization header returns 401",
        dependsOnMethods = "createDuplicateAccount_ShouldReturn406")
    public void deleteUserAccount_WithoutAuthToken_ShouldReturn401() {
        ApiClient.request()
            .when()
            .delete("/Account/v1/User/" + userId)
            .then()
            .statusCode(401);

        logger.info("✓ deleteUserAccount_WithoutAuthToken_ShouldReturn401 PASS — "
            + "account NOT deleted (this call was expected to fail), cleaning up for real next");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Cleanup — actually delete the account this class created, now that every negative
    // case that needed it to still exist has run.
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 11, groups = {"regression", "api"},
        description = "API (negative) cleanup - Delete the account used by this test class",
        dependsOnMethods = {"getUserDetails_WithoutAuthToken_ShouldReturn401",
            "deleteUserAccount_WithoutAuthToken_ShouldReturn401"},
        alwaysRun = true)
    public void deleteUserAccount_ShouldCleanUp() {
        ApiClient.authorizedRequest(validToken)
            .when()
            .delete("/Account/v1/User/" + userId)
            .then()
            .statusCode(204);

        logger.info("✓ deleteUserAccount_ShouldCleanUp PASS — account deleted: " + userId);
        logger.info("=== All Book Store API negative tests completed ===");
    }
}
