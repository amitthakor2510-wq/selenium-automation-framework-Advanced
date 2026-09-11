<div align="center">

# ➕ Extending the Framework

</div>

---

## 📋 Table of Contents
- [➕ Adding a New Site](#-adding-a-new-site--auto-configured-across-all-3-testing-styles)
- [🌐 Adding a New API-Only Site](#-adding-a-new-api-only-site)
- [⚡ Adding a Performance Test for a Site](#-adding-a-performance-test-for-a-site)
- [🧭 Debugging a Live Site Redesign](#-debugging-a-live-site-redesign--lessons-from-a-real-session)

---

## ➕ Adding a New Site — Auto-Configured Across All 3 Testing Styles

Zero changes to core framework files. `Scripts/new-site.sh` scaffolds a
working example of **all three** testing styles this framework supports in
one command — standard Page Object tests, keyword-driven CSV scripts, and
file-driven (data-driven) tests — so a new site is never left with only
one. Jenkins/GitHub/GitLab all auto-discover the new suite files on next run.

### Step 1 — Scaffold everything (one command)
```bash
./Scripts/new-site.sh mysite https://mysite.com
```
Creates:
```text
src/test/resources/config/mysite.properties
testng-suites/mysite-regression.xml
testng-suites/mysite-smoke.xml

# Type 1 — Standard
src/main/java/.../sites/mysite/pages/MysiteHomePage.java
src/test/java/.../sites/mysite/tests/MysiteHomeTest.java

# Type 2 — Keyword-driven
src/test/resources/objectrepository/mysite.properties
src/test/resources/testdata/keyword/mysite_home_keywords.csv
src/test/java/.../sites/mysite/tests/KeywordDrivenMysiteHomeTest.java

# Type 3 — File-driven (data-driven)
src/test/resources/testdata/mysite_home.csv
src/test/java/.../sites/mysite/tests/MysiteHomeDataDrivenTest.java
```

> [!NOTE]
> All three land in the same `com.automation.sites.mysite.tests` package, so
> the generated regression/smoke suite XML picks up all of them automatically
> — no per-type suite wiring needed. Re-running the script against a site
> that already has a `config/<site>.properties` file refuses to run rather
> than clobbering existing work — remove the site's files first (or pick a
> new name) if you really want to regenerate.

### Step 2 — Fill in the placeholders
Each generated file is a real, compiling, runnable stub — not empty
boilerplate — but every locator/URL is a generic placeholder (`css:body`,
an empty `NAVIGATE`, one dummy data row) so it works against literally any
site with zero setup. Replace them with real locators/data once you've
inspected the actual site:
- **Standard**: flesh out `MysiteHomePage.java` with real element locators
  and page methods; add assertions to `MysiteHomeTest.java`.
- **Keyword-driven**: add real `locatorKey` entries to
  `objectrepository/mysite.properties`, then add rows/test cases to
  `mysite_home_keywords.csv` — no Java changes needed for new scenarios.
- **File-driven**: add real columns to `mysite_home.csv` (or swap the path
  for a `.json`/`.yaml`/`.xlsx` file — `DataProviderFactory.fromFile(...)`
  handles all of them identically) and use them in
  `MysiteHomeDataDrivenTest.verifyHomePageLoads(DataRow row)`.

### Step 3 — Run it
```bash
mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-regression.xml
```
Runs all three testing styles together. Filter to just one with `-Dgroups`,
e.g. `-Dgroups=keyword-driven` or `-Dgroups=data-driven`.

---

## 🌐 Adding a New API-Only Site

For a site that's nothing but an API — no browser, no page objects at all. `jsonplaceholder` (see `config/jsonplaceholder.properties`, `sites/jsonplaceholder/tests/JsonPlaceholderApiTest.java`) is the reference example; this is the checklist that built it, unlike `Scripts/new-site.sh` (Step 1 above) there's no scaffold script for this path yet since it's only a handful of files — a good candidate to script the same way if this pattern gets used often.

1. **Register the site** — add one line to `SiteRegistry.KNOWN_SITES` (`src/main/java/com/automation/core/config/SiteRegistry.java`):
   ```java
   "mysite", new SiteDefinition(false)   // false = no object repository required
   ```
1b. **Register it with Test Impact Analysis too** — add one line to `SiteMapper.SITE_TEST_PACKAGE` (`src/main/java/com/automation/core/tia/SiteMapper.java`):
   ```java
   SITE_TEST_PACKAGE.put("mysite", "com.automation.sites.mysite");
   ```
   Easy to forget since nothing fails loudly without it — TIA just silently falls back to its site-blind "unsafe/full suite" decision for every change touching this site instead of scoping to just its tests. (This step is step 1's own sibling for `Scripts/new-site.sh`-scaffolded UI sites, where the script now does it for you automatically; API-only sites have no scaffold script yet, so it's a manual step here. `jsonplaceholder` itself shipped without this line for a while before it was caught in an audit — see `SiteMapper.java`'s own comment.)
2. **Config file** — `src/test/resources/config/mysite.properties`:
   ```properties
   url=https://api.mysite.com
   ```
3. **Enable it + tag it API-only** — `pipeline-config.properties`:
   ```properties
   site.mysite.enabled=true
   site.mysite.type=api
   ```
   The `type=api` line matters: it's what tells `Scripts/enabled-sites.sh --browser-only` (used by GitHub Actions' `matrix-setup` job, and the Jenkins/GitLab equivalents) to exclude this site from the UI browser test matrix — without it, CI would try to spin up a Selenium session against a site with no page objects to test. A site with no `type=` line at all is assumed to be a browser site (every existing UI site predates this convention).
4. **Test class** — extend `BaseApiTest`, use `ApiClient`/`ApiAssertions`/`ApiConfig` as normal:
   ```java
   public class MySiteApiTest extends BaseApiTest {
       @Test(groups = {"smoke", "api"})
       public void getWidget_ShouldReturn200() {
           Response response = ApiClient.get("/widgets/1");
           ApiAssertions.assertStatus(response, 200);
       }
   }
   ```
5. **Suite file** — `testng-suites/api-tests-mysite.xml`, copy `testng-suites/api-tests-jsonplaceholder.xml` as a template and swap the class name. Keep it as its **own suite file**, not a second `<test>` block inside an existing one — `-Dsite` is a single JVM-wide system property (see `ConfigReader`), so a suite mixing two different sites' API test classes can only ever correctly resolve one of them per run. See `testng-suites/api-tests.xml`'s own comment for the full reasoning.
6. **(Optional) JSON schemas** — `src/test/resources/schemas/mysite/*.json` if you want contract validation via `ApiAssertions.assertMatchesSchema(...)`.
7. **Run it**:
   ```bash
   mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/api-tests-mysite.xml
   ```
8. **(Optional) Wire into CI** — add one entry to the `api-tests` job's matrix in `.github/workflows/github-ci.yml` (`{ site: mysite, suiteFile: testng-suites/api-tests-mysite.xml }`); it's self-gating via `Scripts/enabled-sites.sh --check mysite`, so it's a safe no-op until step 3's `enabled=true` lands.

---

## ⚡ Adding a Performance Test for a Site

Works for either a UI site (load-testing a page's raw HTTP response, not driving a real browser) or an API-only site — both go through the exact same `PerfTestBase`. See `docs/testing-guide.md`'s Performance Testing section for the full picture; this is just the "add one for a new site" checklist.

1. **Test class** — extend `core.perf.PerfTestBase`:
   ```java
   public class MySiteHomePagePerfTest extends PerfTestBase {
       @Test(groups = {"perf"})
       public void homePage_ShouldMeetBudget() throws Exception {
           TestPlanStats stats = runGetLoadTest("mysite-home", "/");
           PerfAssertions.assertSamplesRecorded(stats);
           PerfAssertions.assertErrorRateUnder(stats);
           PerfAssertions.assertP99Under(stats);
       }
   }
   ```
2. **Suite file** — `testng-suites/mysite-perf.xml`, copy `testng-suites/demoqa-perf.xml` or `testng-suites/jsonplaceholder-perf.xml` as a template.
3. **Run it**:
   ```bash
   mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-perf.xml -Dgroups=perf
   ```
4. **(Optional) Tune the load profile** per-site — add `perf.threads`/`perf.rampUpSeconds`/`perf.maxP99Millis`/etc. to `config/mysite.properties` if its real-world traffic profile differs from the `global.properties` defaults, same override mechanism as everything else in `PerfConfig`.
5. **(Optional) Wire into CI** — add one matrix entry to the `perf-tests` job in `.github/workflows/github-ci.yml`, same pattern as the API-only checklist's step 8 above.

---

## 🧭 Debugging a Live Site Redesign — Lessons from a Real Session

demoqa.com's Book Store Application was redesigned mid-development of this suite, breaking every previously-working locator at once. The fixes below are documented here because they're the kind of failure mode any test suite pointed at a real, evolving website will eventually hit again — the process matters more than the specific selectors:

| What changed on the site | Symptom | Fix |
|---|---|---|
| React-table grid (`[role='table']`, `.rt-tr-group`, `.rt-noData`) replaced with a plain semantic `<table>` | `TimeoutException` waiting for `[role='table']`, even though the table was visibly on screen | Re-derived locators (`table tbody tr`, `table tbody a[href]`) from a real page-source dump instead of guessing again |
| Book detail links changed from `/books?book=<isbn>` to `/books?search=<isbn>` | URL assertions failed after a successful click | Updated every `urlContains`/`getCurrentUrl().contains(...)` check to the confirmed pattern |
| "Back To Book Store" and "Add To Your Collection" buttons share a duplicate `id="addNewRecordButton"` and are both present at once | `By.id(...)` always resolved to the *first* match — silently clicking the wrong button | Switched to text-based `By.xpath("//button[normalize-space()='...']")` locators |
| Adding a book triggers a **native JS `alert()`**, but deleting one shows a **rendered in-page modal** (not a native dialog) | `alertIsPresent()` correctly caught the add-alert, but timed out on delete and silently moved on with the modal still open, blocking everything after it | Add: accept the native alert. Delete: click the modal's `OK` button directly by locating it in the DOM |
| Profile page collection table renders asynchronously after the username label appears | One-shot `findElements()` reads (`getBookCount()`, `isBookListed()`) intermittently returned stale/empty results depending on timing | Added polling variants (`waitForBookListed(...)`, retry inside `deleteBookByTitle(...)`) instead of trusting a single snapshot right after navigation |

> [!TIP]
> **Takeaways baked into the framework as a result:**
> - `dumpPageForDebugging(String label)` (in `BookStoreApplicationPage` / `ProfilePage`) writes full page source to `target/debug-dumps/` on locator timeout — check there first the next time a previously-passing test breaks against a live site.
> - Prefer **polling** (`wait.until(...)`) over one-shot DOM reads for anything that updates asynchronously after a navigation or an action — a single `findElements()` call has no guarantee the render has settled.
> - Don't assume a confirmation dialog is a native `alert()`/`confirm()` just because a sibling action's confirmation is — check both, or check the actual DOM/screenshot before writing the handling code.

<div align="center">

<sub>⬆️ <a href="#-extending-the-framework">Back to top</a> · <a href="../README.md">← Back to README</a></sub>

</div>
