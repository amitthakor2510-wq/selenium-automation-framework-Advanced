# 🎭 API Mocking (WireMock) & API-Driven Test Data

Two related features that stop the framework depending on the live demoqa.com being up, unrate-limited and clean.

## 1. API-driven setup and teardown

| Class | Role |
|---|---|
| `sites/demoqa/api/DemoQaAccountApi` | REST calls for setup/teardown: create user + token, list/add books, delete user, delete-by-credentials. Throws on an unexpected status, so a broken precondition fails as *setup*, not as a confusing UI failure. |
| `core/api/CleanupRegistry` | LIFO list of undo actions; a failing undo is logged, never rethrown. |
| `sites/core/BaseTest` | `cleanupAfterMethod(...)` / `cleanupAfterClass(...)`, run from their own `@AfterMethod`/`@AfterClass` (independent of subclasses that override `tearDown()`). |
| `sites/core/BaseApiTest` | `cleanupAfterClass(...)` for API-only classes. |
| `sites/demoqa/core/DemoQaBaseTest` | `createApiUserForMethod()`, `createApiUserForClass()`, `deleteAccountAfterClass(user, pw)`. |

`DemoQaAccountApi.real()` always targets the real site (a browser navigates there, so UI-test data must exist there, even when API tests are mocked); `DemoQaAccountApi.current()` follows `-Dmock.enabled`.

What changed in the suite:

- **`ProfileApiSeededTest`** (new): each test gets a fresh account (and book) created over REST *before the browser launches*, no UI registration, no `dependsOnMethods` chain. `deleteFromProfile_IsReflectedInApi` checks the server's record after a UI delete.
- **`BookStoreApplicationTest`**: still registers through the UI (that is what it tests), but now deletes that account by credentials after the class. Previously every run left an `AutoTest_*` user behind.
- **`BookStoreApiTest` / `BookStoreApiNegativeTest`**: a class-level safety net deletes the account by credentials if the chain broke before its own delete step.

## 2. WireMock

Off by default. `mvn test` behaves exactly as before unless you pass `-Dmock.enabled=true`.

```bash
# Offline run of the demoqa API tests + error-state tests
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests-mocked.xml -Dmock.enabled=true

# Any existing API suite, mocked, with zero test changes
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests.xml -Dmock.enabled=true
```

`ApiConfig.baseUri()` returns the local WireMock URL when mocking is on, so every class using `ApiClient` is mocked automatically.

### Where responses come from

1. **Recorded stubs** in `src/test/resources/wiremock/{site}/` (priority 5) — win if present.
2. **`DemoQaBookStoreFake`** (priority 10) — a small stateful in-memory implementation of the Account/BookStore API, matching the codes and messages `BookStoreApiTest` and `BookStoreApiNegativeTest` assert. Needed because a recording replays one fixed answer, which cannot reproduce *create → token → add book → read → delete* with new IDs every run.
3. Anything else → 404 naming the unsupported route.

Its book catalogue is **hand-written seed data** (8 books), not a capture of the live API.

### Recording

```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests.xml -Dmock.enabled=true -Dmock.record=true
```

Proxies to the real site and writes stubs on JVM exit. `demoqa.properties` sets `mock.record.urlPattern` so only the stateless catalogue reads (`/BookStore/v1/Books`) are recorded. Other knobs: `-Dmock.record.target=`, `-Dmock.record.method=GET|ANY`, `-Dmock.record.urlPattern=`. Review recorded files before committing (they contain real response headers).

### Forcing error states

```java
WireMockManager.server().stubFor(get(urlEqualTo("/__mock__/x")).willReturn(serverError()));
```

See `ApiResilienceMockedTest`: a 500, a 503-then-200 (`ApiRetry`), a persistent failure (retry gives up after 1 + N attempts), a slow response tripping the response-time budget. Use URLs no other class touches — the server is shared across parallel classes.

### Limits

- Mocks **HTTP calls made by RestAssured/`ApiClient` only**. A browser under Selenium still hits the real site, so "what does the *UI* show when the API returns 500?" is **not** covered by this; that needs a browser-level proxy or the app pointed at WireMock.
- SauceDemo is a server-rendered UI site with no API in this framework, so there is nothing to mock there.
- `DemoQaBookStoreFakeTest` (JUnit 5) runs in `mvn verify -Punit-tests`.
