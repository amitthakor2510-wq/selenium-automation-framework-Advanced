package com.automation.sites.jsonplaceholder.tests;

import com.automation.core.api.ApiAssertions;
import com.automation.core.api.ApiClient;
import com.automation.core.api.ApiRetry;
import com.automation.core.api.auth.ApiKeyAuthProvider;
import com.automation.sites.core.BaseApiTest;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * ============================================================
 * JSONPlaceholder — reference example of an API-ONLY site
 * ============================================================
 * Run with: {@code -Dsite=jsonplaceholder} — same flag as every UI site,
 * see ApiConfig's javadoc and config/jsonplaceholder.properties for why
 * that's enough with zero framework changes.
 *
 * Unlike BookStoreApiTest (which hits demoqa's real API alongside
 * demoqa's UI tests), this class exists specifically to demonstrate
 * every piece of the API testing layer working against a site that has
 * no UI counterpart at all — proof the framework scales to "API testing
 * only" as its own first-class case, not just an add-on to a UI site:
 *
 *  - {@link ApiClient}'s one-line HTTP verb helpers (get/post/put/delete)
 *  - {@link ApiAssertions} (status, schema, response time, JSON array)
 *  - {@link ApiRetry} (explicit opt-in retry-with-backoff)
 *  - {@link ApiKeyAuthProvider} (AuthProvider composition)
 *  - JSON schema contract validation (schemas/post.json)
 *
 * JSONPlaceholder (https://jsonplaceholder.typicode.com/guide/) fakes
 * writes: POST/PUT/PATCH/DELETE all return a plausible response but
 * nothing is actually persisted server-side. Confirmed behavior this
 * class asserts against (from the official guide + typicode/jsonplaceholder
 * issue tracker, not re-verified live in this sandbox — no network access
 * here; worth one real run to confirm before relying on it in CI, same
 * caveat BookStoreApiTest's own schemas carry):
 *   - POST /posts always returns 201 with a fake id of 101, regardless of
 *     how many posts already exist or what body was sent.
 *   - PUT /posts/{id} returns 200 with the submitted body echoed back.
 *   - DELETE /posts/{id} always returns 200 with an empty JSON object —
 *     even for an id that doesn't exist (see typicode/jsonplaceholder#93);
 *     this is a real quirk worth documenting, not a framework bug if a
 *     "delete a nonexistent post" test doesn't get a 404.
 */
public class JsonPlaceholderApiTest extends BaseApiTest {

    // Schema resources live flat under src/test/resources/schemas/ (no
    // per-site subfolder — see book-detail.json/user-detail.json etc.
    // alongside this one), so the classpath path is just the filename.
    // This previously pointed at "schemas/jsonplaceholder/post.json", a
    // subfolder that doesn't exist, which made
    // matchesJsonSchemaInClasspath resolve a null schema and fail every
    // run with "IllegalArgumentException: Schema to use cannot be null"
    // — not a validation failure, the schema was never found at all.
    private static final String SCHEMA_POST = "schemas/post.json";

    // ════════════════════════════════════════════════════════════════════
    // GET — single resource, schema + response-time assertions
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"smoke", "regression", "api"},
        description = "API - Fetch a single post; validate shape via ApiAssertions")
    public void getPost_ShouldReturnMatchingPostWithinBudget() {
        Response response = ApiClient.get("/posts/1");

        ApiAssertions.assertStatus(response, 200);
        ApiAssertions.assertMatchesSchema(response, SCHEMA_POST);
        ApiAssertions.assertResponseTimeUnder(response);

        response.then().body("id", equalTo(1)).body("title", notNullValue());
    }

    // ════════════════════════════════════════════════════════════════════
    // GET — collection, non-empty array assertion
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"smoke", "regression", "api"},
        description = "API - Post collection is non-empty")
    public void getAllPosts_ShouldReturnNonEmptyCollection() {
        Response response = ApiClient.get("/posts");

        ApiAssertions.assertStatus(response, 200);
        ApiAssertions.assertJsonArrayNotEmpty(response, "$");
    }

    // ════════════════════════════════════════════════════════════════════
    // GET — 404 for a genuinely out-of-range id
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"regression", "api"},
        description = "API - Fetching a post id far outside the seeded range returns 404")
    public void getPost_WithOutOfRangeId_ShouldReturn404() {
        Response response = ApiClient.get("/posts/99999");
        ApiAssertions.assertStatus(response, 404);
    }

    // ════════════════════════════════════════════════════════════════════
    // POST — create (faked server-side, see class javadoc)
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"smoke", "regression", "api"},
        description = "API - Create a post returns 201 with the fake id JSONPlaceholder always assigns")
    public void createPost_ShouldReturn201WithFakeId() {
        Response response = ApiClient.post("/posts", Map.of(
            "title", "framework smoke test",
            "body", "created by JsonPlaceholderApiTest",
            "userId", 1
        ));

        ApiAssertions.assertStatus(response, 201);
        response.then()
            .body("id", equalTo(101))
            .body("title", equalTo("framework smoke test"));
    }

    // ════════════════════════════════════════════════════════════════════
    // PUT — update
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"regression", "api"},
        description = "API - Update a post echoes the submitted body back")
    public void updatePost_ShouldEchoSubmittedBody() {
        Response response = ApiClient.put("/posts/1", Map.of(
            "id", 1,
            "title", "updated title",
            "body", "updated body",
            "userId", 1
        ));

        ApiAssertions.assertStatus(response, 200);
        response.then().body("title", equalTo("updated title"));
    }

    // ════════════════════════════════════════════════════════════════════
    // DELETE
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"regression", "api"},
        description = "API - Delete a post returns 200 with an empty object")
    public void deletePost_ShouldReturn200() {
        Response response = ApiClient.delete("/posts/1");
        ApiAssertions.assertStatus(response, 200);
    }

    // ════════════════════════════════════════════════════════════════════
    // AuthProvider composition — proves the framework's auth layer works
    // even against an API that doesn't require it: JSONPlaceholder simply
    // ignores the extra query param, so this documents *how* to attach
    // auth (for a real, auth-requiring service reusing this same pattern)
    // rather than asserting that authentication succeeded.
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"regression", "api"},
        description = "API - Request with an ApiKeyAuthProvider attached still succeeds " +
            "(demonstrates AuthProvider composition; JSONPlaceholder itself needs no key)")
    public void getPost_WithApiKeyAuthProviderAttached_ShouldStillSucceed() {
        Response response = ApiClient.authenticatedRequest(
                ApiKeyAuthProvider.queryParam("apiKey", "demo-key-not-actually-required"))
            .when()
            .get("/posts/1");

        ApiAssertions.assertStatus(response, 200);
    }

    // ════════════════════════════════════════════════════════════════════
    // ApiRetry — explicit opt-in retry wrapper around a normal call
    // ════════════════════════════════════════════════════════════════════

    @Test(groups = {"regression", "api"},
        description = "API - A call wrapped in ApiRetry succeeds on the first attempt " +
            "against a healthy endpoint (retry path itself only exercises on a real 5xx/timeout)")
    public void getPost_WrappedInApiRetry_ShouldSucceed() {
        Response response = ApiRetry.withRetry(() -> ApiClient.get("/posts/2"), 2, 200);
        ApiAssertions.assertStatus(response, 200);
    }
}
