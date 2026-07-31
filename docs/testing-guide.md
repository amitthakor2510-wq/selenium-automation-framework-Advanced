# Testing Guide

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

> ⚠️ **CI defaults to `retry.count=0`** in both the GitHub Actions workflow and typical Jenkins params, trading resilience for fast, unambiguous CI signal. Locally, leaving the default `2` in place absorbs one-off network/render hiccups without masking a real break.

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

> Tests 11–16 replace what used to be a separate `ProfileTest` class — merged here so the whole flow (register → shop → manage collection → logout) runs against one real user session instead of two independently-registered ones.
</details>

---

## 🌐 Book Store REST API Tests

`BookStoreApiTest` covers the same Book Store domain as the UI flow above, but hits `demoqa.com`'s REST endpoints directly with Rest-Assured — no browser, no Selenium, independent of every other test class. It runs a single account through 9 sequential, dependency-chained tests (`dependsOnMethods`) using one shared `userId`/`authToken`/`sampleIsbn`:

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

- **Test 8** polls up to 3 times (with a short sleep) after the delete before asserting the book is gone — a single immediate `GET` right after `DELETE` occasionally still shows the stale collection.
- **Test 9 expects `204`, not `200`.** DemoQA's own Swagger docs list `200` for `DELETE /Account/v1/User/{UUID}`, but the live API actually returns `204 No Content` — the docs don't match the real response. Asserting `200` here fails every run; this is the one endpoint in the class where the documented contract and the actual behavior disagree.

Run it on its own:
```bash
mvn test -Dtest=BookStoreApiTest
```

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

## ♿🖼️⏱️ Specialized Testing — Accessibility, Visual Regression & Performance

Three opt-in test types beyond standard functional coverage — none run in CI by default (all three are extra network/compute cost per run), each is one explicit command:

### Accessibility — axe-core
`AccessibilityTest` runs a WCAG/GIGW-adjacent scan (via [axe-core](https://www.deque.com/axe/)) against demoqa pages and asserts on violation severity, not just "does the page look right." Relevant specifically for government-portal-style QA subject to GIGW accessibility guidelines, but useful for any UI.
```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml
```
- `a11y.enabled` (config) — turns scanning on/off without removing the test
- `a11y.failOn` (config, default `critical,serious`) — which violation severities actually fail the test vs. just get logged/attached to Allure; tighten once known issues on a page are triaged
- Violations are attached to the Allure report as a readable list, not just a pass/fail

### Visual Regression — AShot
`VisualRegressionTest` captures a pixel-level screenshot of a page and diffs it against a committed baseline image on every subsequent run, catching unintended layout/styling drift that a functional assertion wouldn't notice.
```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-visual.xml
```
- **First run per snapshot name always passes** — it's capturing the baseline. Commit that baseline image; from the second run on, it's a real regression check.
- Only one demoqa page has a baseline so far — extend coverage to more pages as they stabilize (a page whose layout is still actively changing will just generate false-positive diffs).

### Performance Smoke — JMeter
A lightweight response-time/response-code check (not a load or capacity test) via the `perf` Maven profile, so `mvn test` — used everywhere else, CI included — is completely unaffected by its presence.
```bash
mvn verify -Pperf
# tune concurrency/thresholds:
mvn verify -Pperf -Dthreads=10 -DrampUp=5 -Dloops=5 -DmaxResponseMs=3000
```
Plan lives in `perf/basic-smoke.jmx`. Results land in `target/jmeter/results/`, an HTML report in `target/jmeter/reports/`.

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

> **Known gaps, stated plainly:** only one screen (`SettingsHomePage`) is covered so far, and iOS (`IOSDriver`) hasn't been run against a real simulator/device — only the Android path is confirmed live. The Android path itself **is** wired into all three CI pipelines (Jenkins, GitHub Actions, GitLab CI each boot an emulator + Appium server and run the mobile suite) and has been verified end-to-end against a real emulator (Genymotion, Android 15/API 35). See [✅ Verified](../src/main/java/com/automation/mobile/README.md#-verified) in the mobile module's own README, and the [Roadmap](roadmap.md) for the full list of what's still open.

---

## 🚦 Smoke vs Regression

### Smoke — run first, fast
Quick check that critical paths work. Run after every deployment.
```bash
mvn test -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
```

### Regression — run for full coverage
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

> `accessibility`, `visual`, and mobile's own `smoke`/`regression` are separate, **opt-in** groups run via their own suite XML — see [Specialized Testing](#️️-specialized-testing--accessibility-visual-regression--performance) and [Mobile Testing](#-mobile-testing-appium). They're not part of `demoqa-smoke.xml`/`demoqa-regression.xml` and won't run unless you point at their suite file explicitly.

---

