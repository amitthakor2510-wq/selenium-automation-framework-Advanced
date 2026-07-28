# Extending the Framework

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
```
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
All three land in the same `com.automation.sites.mysite.tests` package, so
the generated regression/smoke suite XML picks up all of them automatically
— no per-type suite wiring needed. Re-running the script against a site
that already has a `config/<site>.properties` file refuses to run rather
than clobbering existing work — remove the site's files first (or pick a
new name) if you really want to regenerate.

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

## 🧭 Debugging a Live Site Redesign — Lessons from a Real Session

demoqa.com's Book Store Application was redesigned mid-development of this suite, breaking every previously-working locator at once. The fixes below are documented here because they're the kind of failure mode any test suite pointed at a real, evolving website will eventually hit again — the process matters more than the specific selectors:

| What changed on the site | Symptom | Fix |
|---|---|---|
| React-table grid (`[role='table']`, `.rt-tr-group`, `.rt-noData`) replaced with a plain semantic `<table>` | `TimeoutException` waiting for `[role='table']`, even though the table was visibly on screen | Re-derived locators (`table tbody tr`, `table tbody a[href]`) from a real page-source dump instead of guessing again |
| Book detail links changed from `/books?book=<isbn>` to `/books?search=<isbn>` | URL assertions failed after a successful click | Updated every `urlContains`/`getCurrentUrl().contains(...)` check to the confirmed pattern |
| "Back To Book Store" and "Add To Your Collection" buttons share a duplicate `id="addNewRecordButton"` and are both present at once | `By.id(...)` always resolved to the *first* match — silently clicking the wrong button | Switched to text-based `By.xpath("//button[normalize-space()='...']")` locators |
| Adding a book triggers a **native JS `alert()`**, but deleting one shows a **rendered in-page modal** (not a native dialog) | `alertIsPresent()` correctly caught the add-alert, but timed out on delete and silently moved on with the modal still open, blocking everything after it | Add: accept the native alert. Delete: click the modal's `OK` button directly by locating it in the DOM |
| Profile page collection table renders asynchronously after the username label appears | One-shot `findElements()` reads (`getBookCount()`, `isBookListed()`) intermittently returned stale/empty results depending on timing | Added polling variants (`waitForBookListed(...)`, retry inside `deleteBookByTitle(...)`) instead of trusting a single snapshot right after navigation |

**Takeaways baked into the framework as a result:**
- `dumpPageForDebugging(String label)` (in `BookStoreApplicationPage` / `ProfilePage`) writes full page source to `target/debug-dumps/` on locator timeout — check there first the next time a previously-passing test breaks against a live site.
- Prefer **polling** (`wait.until(...)`) over one-shot DOM reads for anything that updates asynchronously after a navigation or an action — a single `findElements()` call has no guarantee the render has settled.
- Don't assume a confirmation dialog is a native `alert()`/`confirm()` just because a sibling action's confirmation is — check both, or check the actual DOM/screenshot before writing the handling code.

---

