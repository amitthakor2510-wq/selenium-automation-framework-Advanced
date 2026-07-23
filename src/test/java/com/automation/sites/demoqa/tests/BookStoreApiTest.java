package com.automation.sites.demoqa.tests;

import com.automation.core.config.ConfigReader;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * ============================================================
 * Book Store REST API — DemoQA Account + BookStore endpoints
 * ============================================================
 * Swagger reference: https://demoqa.com/swagger/#/
 *
 * Flow (shared state across a single account, sequential):
 *
 *  Test 1  → POST /Account/v1/User            — create account
 *  Test 2  → POST /Account/v1/GenerateToken   — get bearer token
 *  Test 3  → POST /Account/v1/Authorized      — confirm credentials are valid
 *  Test 4  → GET  /BookStore/v1/Books         — catalogue is non-empty (captures an ISBN)
 *  Test 5  → GET  /BookStore/v1/Book?ISBN=..  — fetch that book by ISBN
 *  Test 6  → POST /BookStore/v1/Books         — add the book to the account
 *  Test 7  → GET  /Account/v1/User/{UUID}     — added book shows up on the user
 *  Test 8  → DELETE /BookStore/v1/Book        — remove the book from the account
 *  Test 9  → DELETE /Account/v1/User/{UUID}   — delete the account (cleanup)
 *
 * No browser is used — this is pure HTTP, independent of the Selenium tests.
 */
public class BookStoreApiTest {

    private static final String UNIQUE_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String API_USERNAME = "ApiTest_" + UNIQUE_ID;
    private static final String API_PASSWORD = "Password123!@";

    private static String userId;
    private static String authToken;
    private static String sampleIsbn;

    @BeforeClass(alwaysRun = true)
    public void setBaseUri() {
        ConfigReader.reset();
        RestAssured.baseURI = ConfigReader.get("url"); // https://demoqa.com
        System.out.println("=== Book Store API Test Started — user: " + API_USERNAME + " ===");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 1 — Create account
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 1, groups = {"smoke", "regression", "api"},
        description = "API - Create a new account returns userId + username")
    public void createAccount_ShouldReturnUserIdAndUsername() {
        Response response = given()
            .contentType("application/json")
            .body(Map.of("userName", API_USERNAME, "password", API_PASSWORD))
            .when()
            .post("/Account/v1/User")
            .then()
            .statusCode(201)
            .body("username", equalTo(API_USERNAME))
            .body("userID", not(emptyString()))
            .extract().response();

        userId = response.jsonPath().getString("userID");
        System.out.println("✓ Test 1 PASS — Created userId: " + userId);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 2 — Generate token
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 2, groups = {"smoke", "regression", "api"},
        description = "API - Generate an auth token for the new account",
        dependsOnMethods = "createAccount_ShouldReturnUserIdAndUsername")
    public void generateToken_ShouldReturnValidToken() {
        Response response = given()
            .contentType("application/json")
            .body(Map.of("userName", API_USERNAME, "password", API_PASSWORD))
            .when()
            .post("/Account/v1/GenerateToken")
            .then()
            .statusCode(200)
            .body("status", equalTo("Success"))
            .body("token", not(emptyString()))
            .extract().response();

        authToken = response.jsonPath().getString("token");
        System.out.println("✓ Test 2 PASS — Token generated (len=" + authToken.length() + ")");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 3 — Authorized check
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 3, groups = {"regression", "api"},
        description = "API - Authorized endpoint confirms valid credentials",
        dependsOnMethods = "generateToken_ShouldReturnValidToken")
    public void isAuthorized_ShouldReturnTrueForValidCredentials() {
        given()
            .contentType("application/json")
            .body(Map.of("userName", API_USERNAME, "password", API_PASSWORD))
            .when()
            .post("/Account/v1/Authorized")
            .then()
            .statusCode(200)
            .body(equalTo("true"));

        System.out.println("✓ Test 3 PASS — Account reports authorized=true");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 4 — Get books list
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 4, groups = {"smoke", "regression", "api"},
        description = "API - Book catalogue is non-empty; capture an ISBN for later tests")
    public void getBooksList_ShouldReturnNonEmptyCatalogue() {
        Response response = given()
            .when()
            .get("/BookStore/v1/Books")
            .then()
            .statusCode(200)
            .body("books", not(empty()))
            .extract().response();

        List<String> isbns = response.jsonPath().getList("books.isbn", String.class);
        sampleIsbn = isbns.get(0);
        System.out.println("✓ Test 4 PASS — " + isbns.size() + " books found, using ISBN: " + sampleIsbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 5 — Get book by ISBN
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 5, groups = {"regression", "api"},
        description = "API - Fetch a single book by ISBN returns matching data",
        dependsOnMethods = "getBooksList_ShouldReturnNonEmptyCatalogue")
    public void getBookByIsbn_ShouldReturnMatchingBook() {
        given()
            .queryParam("ISBN", sampleIsbn)
            .when()
            .get("/BookStore/v1/Book")
            .then()
            .statusCode(200)
            .body("isbn", equalTo(sampleIsbn))
            .body("title", not(emptyString()));

        System.out.println("✓ Test 5 PASS — Book detail matches ISBN: " + sampleIsbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 6 — Add book to user's collection
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 6, groups = {"smoke", "regression", "api"},
        description = "API - Add the sample book to the account's collection",
        dependsOnMethods = {"generateToken_ShouldReturnValidToken", "getBookByIsbn_ShouldReturnMatchingBook"})
    public void addBookToUserCollection_ShouldSucceed() {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer " + authToken)
            .body(Map.of(
                "userId", userId,
                "collectionOfIsbns", List.of(Map.of("isbn", sampleIsbn))
            ))
            .when()
            .post("/BookStore/v1/Books")
            .then()
            .statusCode(201)
            .body("books.isbn", hasItem(sampleIsbn));

        System.out.println("✓ Test 6 PASS — Book added to collection: " + sampleIsbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 7 — User details include the added book
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 7, groups = {"regression", "api"},
        description = "API - User detail endpoint lists the added book",
        dependsOnMethods = "addBookToUserCollection_ShouldSucceed")
    public void getUserDetails_ShouldIncludeAddedBook() {
        given()
            .header("Authorization", "Bearer " + authToken)
            .when()
            .get("/Account/v1/User/" + userId)
            .then()
            .statusCode(200)
            .body("username", equalTo(API_USERNAME))
            .body("books.isbn", hasItem(sampleIsbn));

        System.out.println("✓ Test 7 PASS — User's collection contains: " + sampleIsbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 8 — Delete book from collection
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 8, groups = {"regression", "api"},
        description = "API - Delete the book from the account's collection",
        dependsOnMethods = "getUserDetails_ShouldIncludeAddedBook")
    public void deleteBookFromCollection_ShouldSucceed() {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer " + authToken)
            .body(Map.of("isbn", sampleIsbn, "userId", userId))
            .when()
            .delete("/BookStore/v1/Book")
            .then()
            .statusCode(204);

        // A single immediate GET here occasionally still shows the book —
        // the DELETE response and the account record aren't guaranteed to
        // be consistent on the very next read. Poll briefly instead of
        // asserting once, same defensive pattern used for the UI's
        // eventual-consistency waits elsewhere in this project.
        List<String> remainingIsbns = List.of();
        boolean removed = false;
        for (int attempt = 1; attempt <= 3 && !removed; attempt++) {
            remainingIsbns = given()
                .header("Authorization", "Bearer " + authToken)
                .when()
                .get("/Account/v1/User/" + userId)
                .then()
                .statusCode(200)
                .extract().response()
                .jsonPath().getList("books.isbn", String.class);
            removed = !remainingIsbns.contains(sampleIsbn);
            if (!removed && attempt < 3) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        Assert.assertFalse(remainingIsbns.contains(sampleIsbn),
            "Book " + sampleIsbn + " should be removed from collection after 3 checks");

        System.out.println("✓ Test 8 PASS — Book removed from collection: " + sampleIsbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 9 — Delete account (cleanup)
    // ════════════════════════════════════════════════════════════════════════════

    @Test(priority = 9, groups = {"regression", "api"},
        description = "API - Delete the account used by this test class",
        dependsOnMethods = "deleteBookFromCollection_ShouldSucceed", alwaysRun = true)
    public void deleteUserAccount_ShouldCleanUp() {
        given()
            .header("Authorization", "Bearer " + authToken)
            .when()
            .delete("/Account/v1/User/" + userId)
            .then()
            .statusCode(204);

        System.out.println("✓ Test 9 PASS — Account deleted: " + userId);
        System.out.println("=== All 9 Book Store API tests completed ===");
    }
}
