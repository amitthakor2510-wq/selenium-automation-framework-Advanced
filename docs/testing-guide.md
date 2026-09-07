<div align="center">

# 🧪 Testing Guide

</div>

---

## 📋 Table of Contents
- [🔁 Retry & Resilience](#-retry--resilience)
- [🧩 Test Coverage — demoqa.com](#-test-coverage--demoqacom)
- [🌐 API Testing](#-api-testing)
- [🧵 Keyword-Driven & Data-Driven Testing](#-keyword-driven--data-driven-testing)
- [♿🖼️ Specialized Testing — Accessibility & Visual Regression](#️️-specialized-testing--accessibility--visual-regression)
- [⚡ Performance Testing](#-performance-testing)
- [🧬 Synthetic/Generated Test Data](#-syntheticgenerated-test-data)
- [📱 Mobile Testing (Appium)](#-mobile-testing-appium)
- [🚦 Smoke vs Regression](#-smoke-vs-regression)

---

## 🔁 Retry & Resilience

Two listeners work together so no test needs to opt in manually:

| File | Role |
|---|---|
| `RetryListener.java` | An `IAnnotationTransformer` — attaches `RetryAnalyzer` to **every** `@Test` at runtime, so individual test classes never need `retryAnalyzer = ...` boilerplate. |
| `RetryAnalyzer.java` | An `IRetryAnalyzer` — on failure, re-queues the test up to `retry.count` times (default **2**, from `global.properties`; override with `-Dretry.count=N`). |

```bash
# Disable retry entirely — useful when you want a failure to surface immediately
mvn test -Dretry.count=0 -Dtest=BookStoreApplicationTest
```

> [!WARNING]
> **CI defaults to `retry.count=0`** in both the GitHub Actions workflow and typical Jenkins params, trading resilience for fast, unambiguous CI signal. Locally, leaving the default `2` in place absorbs one-off network/render hiccups without masking a real break.

Page objects add a second layer of resilience beyond retry: several locators are wrapped to dump the full page source to `target/debug-dumps/*.html` on a `TimeoutException`/`NoSuchElementException`, rather than failing with only a stack trace. See [🧭 Debugging a Live Site Redesign](extending.md#-debugging-a-live-site-redesign--lessons-from-a-real-session) for why that pattern exists and how to use the dumps it produces.

---

## 🧩 Test Coverage — demoqa.com

<details>
<summary><strong>Elements Section</strong></summary>

| Page | Description | Groups |
|---|---|---|
| Text Box | Fill and submit form, verify output | smoke, regression |
| Check Box | Expand tree, select Desktop checkbox | regression |
| Radio Button | Select Yes option, verify result | regression |
| Web Tables | Full CRUD — add, search, edit, delete | regression |
| Buttons | Double click, right click, dynamic click | regression |
| Links | Home link opens tab, API links return correct status codes | smoke, regression |
| Broken Links - Images | Valid image loads, broken image fails, link navigation | smoke, regression |
| Upload and Download | Upload file, download file to target folder | smoke, regression |
| Dynamic Properties | Enable after delay, color change, appear after delay | smoke, regression |
</details>

<details>
<summary><strong>Forms Section</strong></summary>

| Page | Description | Groups |
|---|---|---|
| Practice Form | Full form with all fields, mandatory fields only | smoke, regression |
</details>

<details>
<summary><strong>Alerts, Frame and Windows Section</strong></summary>

| Page | Description | Groups |
|---|---|---|
| Browser Windows | New tab, new window, message window | smoke, regression |
| Alerts | Simple alert, timer alert, confirm accept/dismiss, prompt | smoke, regression |
| Frames | Read text from frame 1 and frame 2 | smoke, regression |
| Nested Frames | Read parent frame text, child frame text | smoke, regression |
| Modal Dialogs | Small modal title/body, large modal title/body | smoke, regression |
</details>

<details>
<summary><strong>Widgets Section</strong></summary>

| Page | Description | Groups |
|---|---|---|
| Accordian | Section 1 default open, open section 2, open section 3 | smoke, regression |
| Auto Complete | Multi color select, single color select | smoke, regression |
| Date Picker | Select specific date from calendar | smoke, regression |
| Slider | Set value to 50, set value to 75 | smoke, regression |
| Progress Bar | Starts at 0, reaches 100, resets to 0 | smoke, regression |
| Tabs | What tab content, origin tab, use tab | smoke, regression |
| Tool Tips | Button tooltip on hover, text field tooltip on hover | smoke, regression |
| Menu | Main item visible, sub item on hover, nested sub sub item | smoke, regression |
| Select Menu | Old style select, standard multi select | smoke, regression |
</details>

<details open>
<summary><strong>Book Store Application — full E2E flow (16 tests, one shared session)</strong></summary>

`BookStoreApplicationTest` drives the entire Book Store Application as one continuous logged-in session rather than isolated page checks — registration through logout, with the profile/collection flow folded in as part of the same journey instead of a separate test class:

| # | Test | What it verifies |
|---|---|---|
| 1–3 | Register → back to login | New unique user registers successfully, lands back on `/login` |
| 4–5 | Invalid then valid login | Bad credentials rejected with an error message; correct credentials log in |
| 6–10 | Browse, search, open a book | Store lists books, search filters correctly, detail page shows ISBN/Author |
| 11–12 | Profile — identity & empty state | Profile shows the logged-in username; a new user's collection is empty |
| 13 | Add to collection | Adds a book from its detail page, accepts the resulting native alert |
| 14 | Book appears on profile | Polls until the added book is visible in the collection (see note below) |
| 15 | Delete from profile | Confirms the in-page delete modal, polls until the row is gone |
| 16 | Logout | Redirects to `/login`, closing out the session |

> [!NOTE]
> Tests 11–16 replace what used to be a separate `ProfileTest` class — merged here so the whole flow (register → shop → manage collection → logout) runs against one real user session instead of two independently-registered ones.
</details>

---

## 🌐 API Testing

Pure-HTTP tests — no browser, no Selenium/Grid — built on Rest-Assured plus this framework's own thin layer around it (`core/api/`). Two flavors, both first-class:

- **Site-coupled** — a site that has both UI and API coverage (`BookStoreApiTest`/`BookStoreApiNegativeTest` against demoqa's REST endpoints, run with the same `-Dsite=demoqa` the UI tests use).
- **Standalone / API-only** — a site that's nothing but an API, registered the same way as any UI site (`SiteRegistry`, `pipeline-config.properties`, a `config/{site}.properties` file) but with no page objects at all. `JsonPlaceholderApiTest` (against the public [JSONPlaceholder](https://jsonplaceholder.typicode.com) API) is the reference example — see [➕ Adding a New API-Only Site](extending.md#-adding-a-new-api-only-site) for the checklist to add your own.

### 🧰 The API framework (`core/api/`)

| Class | What it's for |
|---|---|
| `ApiClient` | Base-URI setup (`ApiClient.configure()`), request builders (`jsonRequest()`/`request()`), and one-line HTTP verb helpers (`get/post/put/patch/delete(path)`) |
| `ApiConfig` | Resolves the active site's base URI + retry/timeout defaults — same `ConfigReader` mechanism (`global.properties` → `{site}.properties` → `-D` override) as everything else in this framework |
| `AuthProvider` (+`BearerTokenAuthProvider`/`BasicAuthProvider`/`ApiKeyAuthProvider`) | Pluggable auth strategies, composed via `ApiClient.authenticatedRequest(provider)` — add a new scheme (OAuth2, HMAC, ...) by implementing one interface method, no `ApiClient` changes needed |
| `ApiAssertions` | One-line status/schema/response-time/header/array assertions with readable failure messages (response body attached on a status mismatch) |
| `ApiRetry` | Explicit, opt-in exponential-backoff retry for a single flaky call — `ApiRetry.withRetry(() -> ApiClient.get(path))`. Not automatic on every call: most API failures here are real contract violations worth surfacing immediately, not blips worth masking |

```java
Response response = ApiClient.get("/posts/1");
ApiAssertions.assertStatus(response, 200);
ApiAssertions.assertMatchesSchema(response, "schemas/jsonplaceholder/post.json");
ApiAssertions.assertResponseTimeUnder(response);   // uses api.responseTime.maxMs
```

Every call made through `ApiClient` is attached to the Allure report automatically (request + response + timing), regardless of pass/fail — see `ApiClient.configure()`'s `AllureRestAssured` filter.

### 📚 Book Store REST API Tests (demoqa)

`BookStoreApiTest` runs a single account through 9 sequential, dependency-chained tests (`dependsOnMethods`) using one shared `userId`/`authToken`/`sampleIsbn`:

| # | Test | Endpoint |
|---|---|---|
| 1 | Create account | `POST /Account/v1/User` |
| 2 | Generate token | `POST /Account/v1/GenerateToken` |
| 3 | Confirm authorized | `POST /Account/v1/Authorized` |
| 4 | Book catalogue non-empty (captures an ISBN) | `GET /BookStore/v1/Books` |
| 5 | Fetch that book by ISBN | `GET /BookStore/v1/Book?ISBN=...` |
| 6 | Add book to account | `POST /BookStore/v1/Books` |
| 7 | Book shows up on the user | `GET /Account/v1/User/{UUID}` |
| 8 | Remove book from account | `DELETE /BookStore/v1/Book` |
| 9 | Delete the account (cleanup, `alwaysRun`) | `DELETE /Account/v1/User/{UUID}` |

Two eventual-consistency details worth knowing if you're extending this class:

> [!IMPORTANT]
> - **Test 8** polls up to 3 times (with a short sleep) after the delete before asserting the book is gone — a single immediate `GET` right after `DELETE` occasionally still shows the stale collection.
> - **Test 9 expects `204`, not `200`.** DemoQA's own Swagger docs list `200` for `DELETE /Account/v1/User/{UUID}`, but the live API actually returns `204 No Content` — the docs don't match the real response. Asserting `200` here fails every run; this is the one endpoint in the class where the documented contract and the actual behavior disagree.

Run it on its own:
```bash
mvn test -Dtest=BookStoreApiTest
# or the full suite (both demoqa API test classes):
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests.xml
```

### 🌍 Standalone API-Only Sites (JsonPlaceholderApiTest)

`JsonPlaceholderApiTest` hits the public [JSONPlaceholder](https://jsonplaceholder.typicode.com) fake REST API and exists specifically to demonstrate the framework's API layer working against a site with **no UI counterpart at all** — GET/POST/PUT/DELETE, schema validation, `ApiAssertions`, `ApiRetry`, and `AuthProvider` composition, all in one class.

```bash
mvn test -Dsite=jsonplaceholder -DsuiteXmlFile=testng-suites/api-tests-jsonplaceholder.xml
```

> [!NOTE]
> This runs as its own suite file, not folded into `api-tests.xml` — `-Dsite` is a single JVM-wide system property (see `ConfigReader`), so one `mvn test` invocation can only resolve one site's base URI at a time. `BookStoreApiTest` needs `-Dsite=demoqa`; `JsonPlaceholderApiTest` needs `-Dsite=jsonplaceholder`. Two suite files, each its own `mvn test -Dsite=...` run, is what actually keeps both correct.

### 📐 API Contract Validation

Tests 1, 4, 5, and 7 of `BookStoreApiTest` also assert the *whole shape* of the response, not just the specific field values the table above implies:

```java
.body(matchesJsonSchemaInClasspath("schemas/bookstore/account-created.json"))
.body("username", equalTo(API_USERNAME))
.body("userID", not(emptyString()))
```

Or, using the new `ApiAssertions` helper (equivalent, usable outside a `.then()` chain — e.g. after `ApiRetry.withRetry`):
```java
ApiAssertions.assertMatchesSchema(response, "schemas/bookstore/account-created.json");
```

Schema validation and field-by-field Hamcrest assertions catch different things, and neither replaces the other:
- **A field-by-field assertion** (`equalTo`, `hasItem`, ...) catches a specific value being *wrong* — e.g. `username` echoing back something other than what was submitted.
- **Schema validation** catches the response's *shape* changing — a field disappearing, being renamed, or switching type (say, `pages` starting to come back as a string) — even when every field the Hamcrest assertions happen to check still passes.

The schemas live under `src/test/resources/schemas/<site>/` — `bookstore/` for demoqa's 4 schemas, `jsonplaceholder/post.json` for the standalone site:

| Schema | Validates | Used in |
|---|---|---|
| `bookstore/account-created.json` | `POST /Account/v1/User` | `BookStoreApiTest` Test 1 |
| `bookstore/books-list.json` | `GET /BookStore/v1/Books` | `BookStoreApiTest` Test 4 |
| `bookstore/book-detail.json` | `GET /BookStore/v1/Book?ISBN=...` | `BookStoreApiTest` Test 5 |
| `bookstore/user-detail.json` | `GET /Account/v1/User/{UUID}` | `BookStoreApiTest` Test 7 |
| `jsonplaceholder/post.json` | `GET/POST/PUT /posts...` | `JsonPlaceholderApiTest` |

> [!IMPORTANT]
> `user-detail.json` documents a real quirk: `GET /Account/v1/User/{UUID}` returns the id field as `userId` (lowercase d), while `POST /Account/v1/User`'s creation response uses `userID` (capital D) for what is otherwise the same value. That's DemoQA's own API being inconsistent between endpoints, not a typo in the schema — see the comment in that file.

Each schema's field list was written from what the live API is documented and known to return, matching what the corresponding test class already asserts field-by-field — not re-verified against a fresh live response in this pass, since this sandbox has no network access. Worth one real run of the relevant suite to confirm before relying on these in CI; if a field name or type is off, the failure will point at exactly which schema and which field.

---

## 🧵 Keyword-Driven & Data-Driven Testing

`saucedemo` (saucedemo.com's login page) exists specifically to demonstrate three different ways to write the *same* test, so a new project can pick whichever style fits:

| Test class | Style | Where the values live |
|---|---|---|
| `LoginTest` | Classic | Hardcoded in the Java method |
| `LoginDataDrivenTest` | Data-driven | `testdata/login.{csv,json,xlsx,yaml,zip}` via `@Test(dataProvider = ...)` — same test method, 5 interchangeable file formats |
| `KeywordDrivenLoginTest` | Keyword-driven | `testdata/keyword/saucedemo_login_keywords.csv` — each row is a step (`NAVIGATE`, `TYPE`, `CLICK`, `VERIFY_DISPLAYED`, ...); locators come from `objectrepository/saucedemo.properties`, not the test |

The keyword-driven style is the one worth understanding if the goal is letting non-Java teammates add coverage: a new scenario is a new block of CSV rows, no Java compile required, unless it needs an assertion outside the existing `Keyword` enum. `demoqa` carries a second, independent example of the same style — `KeywordDrivenTextBoxTest`, scripted from `testdata/keyword/demoqa_textbox_keywords.csv` against `objectrepository/demoqa.properties` — so you have two real reference implementations to copy from, not just one. Full details, including the exact CSV schema and a worked example of adding a new scenario, live in **[`KEYWORD_DRIVEN_TESTING.md`](../KEYWORD_DRIVEN_TESTING.md)**.

```bash
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-smoke.xml
```

Adding a brand-new site with all three styles already scaffolded is one command — see [➕ Adding a New Site](extending.md#-adding-a-new-site--auto-configured-across-all-3-testing-styles).

---

## ♿🖼️ Specialized Testing — Accessibility & Visual Regression

Two opt-in test types beyond standard functional coverage — neither runs in CI by default (extra network/compute cost per run), each is one explicit command:

### ♿ Accessibility — axe-core
`AccessibilityTest` runs a WCAG/GIGW-adjacent scan (via [axe-core](https://www.deque.com/axe/)) against demoqa pages and asserts on violation severity, not just "does the page look right." Relevant specifically for government-portal-style QA subject to GIGW accessibility guidelines, but useful for any UI.
```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml
```
- `a11y.enabled` (config) — turns scanning on/off without removing the test
- `a11y.failOn` (config, default `critical,serious`) — which violation severities actually fail the test vs. just get logged/attached to Allure; tighten once known issues on a page are triaged
- Violations are attached to the Allure report as a readable list, not just a pass/fail

### 🖼️ Visual Regression — AShot
`VisualRegressionTest` captures a pixel-level screenshot of a page and diffs it against a committed baseline image on every subsequent run, catching unintended layout/styling drift that a functional assertion wouldn't notice.
```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-visual.xml
```

> [!TIP]
> **First run per snapshot name always passes** — it's capturing the baseline. Commit that baseline image; from the second run on, it's a real regression check. Only one demoqa page has a baseline so far — extend coverage to more pages as they stabilize (a page whose layout is still actively changing will just generate false-positive diffs).

---

## ⚡ Performance Testing

Two complementary ways to load-test this framework's sites — pick based on what you need, they measure the same kind of thing (server/network response time and error rate under concurrent load, never client-side rendering — that's what the real-browser UI suite already covers) but at very different levels of investment:

| | 🪶 JMeter smoke (legacy) | ☕ Java DSL (recommended) |
|---|---|---|
| Where it lives | `perf/basic-smoke.jmx` (hand-edited XML) | `core/perf/` + a normal `*PerfTest` class per site |
| How you run it | `mvn verify -Pperf` (separate Maven profile) | `mvn test -DsuiteXmlFile=testng-suites/<site>-perf.xml -Dgroups=perf` (same `mvn test` everything else uses) |
| Config source | Command-line `-D` flags only | `ConfigReader` (`global.properties` → `{site}.properties` → `-D`) — same as every other test type |
| Reporting | Its own JMeter HTML report only | Allure/ExtentReports/ReportPortal (automatic — it's a normal TestNG `@Test`) **+** a JMeter DSL HTML report/JTL under `target/perf-reports/` |
| Best for | A quick, no-code eyeball check, or opening the plan in the JMeter GUI | Everything else — reviewable as a Java diff, reuses this framework's site/config/reporting stack, assertions on p99/error-rate as real TestNG failures |

### ☕ Load tests — Java DSL (recommended)

Built on [jmeter-java-dsl](https://abstracta.github.io/jmeter-java-dsl/) (see `pom.xml`) — a real embedded JMeter engine underneath, but the test plan itself is ordinary, code-reviewable Java instead of hand-edited XML. `core/perf/PerfTestBase` is the base class every perf test extends:

```java
public class DemoQaHomePagePerfTest extends PerfTestBase {
    @Test(groups = {"perf"})
    public void homePage_ShouldMeetResponseTimeAndErrorRateBudget() throws Exception {
        TestPlanStats stats = runGetLoadTest("demoqa-home", "/");
        PerfAssertions.assertSamplesRecorded(stats);
        PerfAssertions.assertErrorRateUnder(stats);   // uses perf.maxErrorRatePercent
        PerfAssertions.assertP99Under(stats);          // uses perf.maxP99Millis
    }
}
```

Run it:
```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf
mvn test -Dsite=jsonplaceholder -DsuiteXmlFile=testng-suites/jsonplaceholder-perf.xml -Dgroups=perf

# tune the load profile:
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf \
  -Dperf.threads=25 -Dperf.rampUpSeconds=10 -Dperf.iterations=10 -Dperf.maxP99Millis=3000
```

`runGetLoadTest(reportName, relativePath)` covers the common single-GET-endpoint case, resolving `relativePath` against the active site's base URI (same `ApiConfig`/`ConfigReader` path an API test uses). For a multi-step scenario (login then an authenticated page load, or a POST with a body), build a `DslDefaultThreadGroup` directly and pass it to `PerfTestBase.run(reportName, group)` — see `PerfTestBase`'s own javadoc.

`PerfConfig` (`global.properties`' "PERFORMANCE TESTING" section) controls the defaults every perf test reads unless overridden per-run:

| Key | Default | Meaning |
|---|---|---|
| `perf.threads` | 10 | Peak concurrent virtual users |
| `perf.rampUpSeconds` | 5 | Seconds to ramp 0 → `perf.threads` |
| `perf.iterations` | 5 | Iterations per thread after ramp-up |
| `perf.maxP99Millis` | 5000 | p99 response-time budget `PerfAssertions.assertP99Under(stats)` checks |
| `perf.maxErrorRatePercent` | 1.0 | Max acceptable error rate `PerfAssertions.assertErrorRateUnder(stats)` checks |
| `perf.reportBaseDir` | `target/perf-reports` | Where the HTML report + JTL land, one subfolder per test |

> [!NOTE]
> Perf tests are opt-in (`group "perf"`, excluded from `smoke`/`regression` — same convention as the synthetic-data tests) since a load test's pass/fail depends on response-time/error-rate budgets that are environment-sensitive: a shared, variably-loaded CI runner isn't a fair baseline for a tight p99 assertion. In CI, the `perf-tests` job (GitHub Actions) runs these nightly rather than on every push — see `docs/ci-cd.md`.

Adding a perf test for a new site: extend `PerfTestBase`, add one `@Test(groups = {"perf"})` method calling `runGetLoadTest(...)`, and a `<site>-perf.xml` suite file (copy `demoqa-perf.xml` or `jsonplaceholder-perf.xml` as a template) — no other framework code changes needed.

### 🪶 Quick smoke — JMeter (legacy)

A lightweight response-time/response-code check (not a load or capacity test) via the `perf` Maven profile, so `mvn test` — used everywhere else, CI included — is completely unaffected by its presence.
```bash
mvn verify -Pperf
# tune concurrency/thresholds:
mvn verify -Pperf -Dthreads=10 -DrampUp=5 -Dloops=5 -DmaxResponseMs=3000
```
Plan lives in `perf/basic-smoke.jmx`. Results land in `target/jmeter/results/`, an HTML report in `target/jmeter/reports/`. Kept as-is (untouched by the Java DSL addition above) since it's still the quickest way to eyeball a response-time smoke check or open the plan in the JMeter GUI — not wired into any CI job, local/manual use only.

---

## 🧬 Synthetic/Generated Test Data

`RegistrationSyntheticDataTest` (demoqa) exercises Book Store registration with generated data instead of a hand-written CSV/Excel row — two complementary flavors, both built on [DataFaker](https://www.datafaker.net/) (see `core/data/synthetic/SyntheticDataGenerator.java`):

- **Realistic** — Faker-generated names/emails/usernames/passwords, for happy-path coverage across a wider variety of inputs than a handful of hand-typed rows ever would.
- **Edge case** — a fixed, deliberately awkward set of boundary values (empty, whitespace-only, a single character, a 300-character run, unicode/emoji, SQL-injection-/XSS-shaped strings) applied to the username field, for exercising validation a realistic-looking value would never trigger — a lightweight, property-based-testing-style check without pulling in a full property-based-testing library.

```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-synthetic-data.xml
# tune row count / reproducibility:
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-synthetic-data.xml -Dsynthetic.data.count=10 -Dsynthetic.data.seed=12345
```

> [!TIP]
> **Opt-in and sequential, not part of `demoqa-smoke.xml`/`demoqa-regression.xml` or CI** — DemoQA's real registration endpoint is ReCaptcha rate-limited (see `RegistrationPage.isRegistrationSuccessful`'s javadoc), so this deliberately stays a small, explicit, non-parallel run rather than something that fires on every push. A row that hits the rate limit is skipped, not failed — that's a known site-side limit, not a defect.

Reusable outside this one test class via `DataProviderFactory.syntheticRegistrations(count)` / `.syntheticRegistrations()` (count from `synthetic.data.count`) / `.syntheticRegistrationEdgeCases()` — or `SyntheticDataGenerator` directly for a one-off value (`new SyntheticDataGenerator().email()`, `.strongPassword()`, etc.) in a new test of your own. See `synthetic.data.count` / `synthetic.data.seed` in [Configuration](configuration.md).

---

## 📱 Mobile Testing (Appium)

A separate Appium module (`com.automation.mobile`) for Android/iOS app testing, alongside — not instead of — the browser framework above. Same Page Object + TestNG + Allure/Extent shape as every web test, just against a mobile driver instead of a browser one.

| Layer | Web equivalent | Mobile file |
|---|---|---|
| Driver factory | `DriverFactory` | `mobile/core/AppiumDriverFactory.java` |
| Page Object base | `BasePage` | `mobile/core/BaseMobilePage.java` |
| Test base | `sites/core/BaseTest` | `mobile/core/MobileBaseTest.java` (implements `DriverProvider`, so the existing `TestListener` screenshot-on-failure hookup works unmodified) |
| Example screen + test | a `LoginPage` + `LoginTest` | `SettingsHomePage.java` + `SettingsHomeTest.java` — against Android's **built-in Settings app**, so the module runs end-to-end on any emulator/device with zero app-under-test setup |

### Getting it running
```bash
# 1. Start the Appium server (separately installed — npm install -g appium)
appium

# 2. Copy the example config and fill in real values
cp src/test/resources/config/mobile.properties.example \
   src/test/resources/config/mobile.properties
# edit mobile.device.name to match `adb devices`; leave mobile.app.path empty
# and set mobile.app.package=com.android.settings / mobile.app.activity=.Settings
# for the built-in example, or point both at your own app instead

# 3. Run it — same ConfigReader mechanism as every other site, just
#    with -Dsite=mobile, no framework code changes needed
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
```

### Adding your own app
1. Add screen objects under `com.automation.mobile.sites.<app>.pages`, extending `BaseMobilePage`, using real element IDs/accessibility labels pulled via `appium inspector` or `uiautomatorviewer` (the mobile equivalent of browser DevTools).
2. Add test classes under `com.automation.mobile.sites.<app>.tests`, extending `MobileBaseTest` — same pattern as `SettingsHomeTest`.
3. Point `mobile.app.path` (fresh install from a local `.apk`/`.ipa`) or `mobile.app.package` + `mobile.app.activity` (already-installed app) at your app in `mobile.properties`.

Full setup guide, including remote/cloud grid config (BrowserStack, Sauce Labs): **[`src/main/java/com/automation/mobile/README.md`](../src/main/java/com/automation/mobile/README.md)**.

> [!NOTE]
> **Known gaps, stated plainly:** only one screen (`SettingsHomePage`) is covered so far, and iOS (`IOSDriver`) hasn't been run against a real simulator/device — only the Android path is confirmed live. The Android path itself **is** wired into all three CI pipelines (Jenkins, GitHub Actions, GitLab CI each boot an emulator + Appium server and run the mobile suite) and has been verified end-to-end against a real emulator (Genymotion, Android 15/API 35). See [✅ Verified](../src/main/java/com/automation/mobile/README.md#-verified) in the mobile module's own README, and the [Roadmap](roadmap.md) for the full list of what's still open.

---

## 🚦 Smoke vs Regression

### 🟢 Smoke — run first, fast
Quick check that critical paths work. Run after every deployment.
```bash
mvn test -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
```

### 🔵 Regression — run for full coverage
Complete suite. Run nightly or before submitting a report.
```bash
mvn test -DsuiteXmlFile=testng-suites/demoqa-regression.xml
```

### How groups are assigned in code
```java
@Test(groups = {"smoke"})               // smoke only
@Test(groups = {"regression"})          // regression only
@Test(groups = {"smoke", "regression"}) // both suites
```

> [!NOTE]
> `accessibility`, `visual`, `perf`, `synthetic-data`, and mobile's own `smoke`/`regression` are separate, **opt-in** groups run via their own suite XML — see [Specialized Testing](#️️-specialized-testing--accessibility--visual-regression), [Performance Testing](#-performance-testing), [Synthetic/Generated Test Data](#-syntheticgenerated-test-data), and [Mobile Testing](#-mobile-testing-appium). They're not part of `demoqa-smoke.xml`/`demoqa-regression.xml` and won't run unless you point at their suite file explicitly. The `api` group is different: `BookStoreApiTest`/`BookStoreApiNegativeTest` are also tagged `smoke`/`regression`, so they already run as part of those suites — `testng-suites/api-tests.xml` (see [API Testing](#-api-testing)) just lets you run *only* the API tests, fast, without the browser/Grid setup the full suite needs. `JsonPlaceholderApiTest` is the exception among API tests: it's genuinely standalone since it targets a different site (`-Dsite=jsonplaceholder`) than `demoqa-smoke.xml`/`-regression.xml` run against.

<div align="center">

<sub>⬆️ <a href="#-testing-guide">Back to top</a> · <a href="../README.md">← Back to README</a></sub>

</div>
