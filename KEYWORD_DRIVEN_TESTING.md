<div align="center">

# 🧵 Keyword-Driven Testing
### (+ Keyboard-Only Flows)

</div>

---

New package: `com.automation.core.keyword`. A keyword-driven test case is a
block of rows in a data file (Excel/CSV/JSON/YAML — anything `DataProvider`
already reads) instead of a Java method per scenario. Locators live in a
separate "object repository" properties file, not in the test or the script.

## 📋 Table of Contents
- [🧩 Pieces](#-pieces)
- [📁 Files Added as a Working Example (SauceDemo Login)](#-files-added-as-a-working-example-saucedemo-login)
- [➕ Adding a New Scenario](#-adding-a-new-scenario)
- [🎯 Adding a New Locator](#-adding-a-new-locator)
- [⌨️ Keyboard-Only ("Keyboard-Driven") Testing](#️-keyboard-only-keyboard-driven-testing)
- [🔐 Keeping Credentials Out of Scripts (Placeholders)](#-keeping-credentials-out-of-scripts-placeholders)
- [🔗 DDT Enhancements Used Alongside This](#-ddt-enhancements-used-alongside-this)

---

## 🧩 Pieces

| Class | Role |
|---|---|
| `Keyword` | Enum of supported actions (NAVIGATE, CLICK, TYPE, SET_TEXT, CLEAR, SELECT_BY_TEXT/VALUE, HOVER, SCROLL_TO, WAIT_SECONDS, PRESS_KEY, VERIFY_*, SWITCH_TO_FRAME/DEFAULT_CONTENT, ACCEPT/DISMISS_ALERT, SOLVE_TEXT_CAPTCHA/SOLVE_MATH_CAPTCHA/SOLVE_CAPTCHA_WITH_AI — see [🧩 CAPTCHA Solver](docs/CAPTCHA_SOLVER.md), SCREENSHOT) |
| `KeywordStep` | One script row: `testCase, stepNo, keyword, locatorKey, testData, expected, description` |
| `ObjectRepository` | Loads `type:value` locators from a `.properties` file, e.g. `saucedemo.login.username=id:user-name` |
| `KeywordReader` | Reads a script file and groups rows into ordered `List<KeywordStep>` per `testCase` |
| `KeywordEngine` | Executes a `List<KeywordStep>` against a live `WebDriver` |
| `KeywordTestBase` | `extends BaseTest`; gives test classes `runKeywordTestCase(objectRepo, scriptPath, testCase)` |

---

## 📁 Files Added as a Working Example (SauceDemo Login)

- `src/test/resources/objectrepository/saucedemo.properties` — locators
- `src/test/resources/testdata/keyword/saucedemo_login_keywords.csv` — 3 scripted cases
- `src/test/java/com/automation/sites/saucedemo/tests/KeywordDrivenLoginTest.java` — runs them

---

## ➕ Adding a New Scenario

Add a new `testCase` block to the CSV (or any supported format) — no new
Java needed unless you need an assertion outside the existing keyword set:

```csv
testCase,stepNo,keyword,locatorKey,testData,expected,description
TC04_EmptyPasswordShowsError,1,NAVIGATE,,,,Go to login page
TC04_EmptyPasswordShowsError,2,TYPE,saucedemo.login.username,standard_user,,Enter username
TC04_EmptyPasswordShowsError,3,CLICK,saucedemo.login.submitButton,,,Submit with no password
TC04_EmptyPasswordShowsError,4,VERIFY_DISPLAYED,saucedemo.login.errorMessage,,,Error banner should show
```

Then in the test class:
```java
@Test
public void emptyPasswordShowsError() {
    runKeywordTestCase(OBJECT_REPO, SCRIPT, "TC04_EmptyPasswordShowsError");
}
```

---

## 🎯 Adding a New Locator

Add one line to the relevant `objectrepository/<site>.properties` file:
```properties
saucedemo.inventory.addToCartButton=css:[data-test='add-to-cart-sauce-labs-backpack']
```

> [!TIP]
> Supported locator prefixes: `id`, `name`, `css`, `xpath`, `class`, `linktext`, `partiallinktext`, `tag`.

---

## ⌨️ Keyboard-Only ("Keyboard-Driven") Testing

`PRESS_KEY` sends a raw `org.openqa.selenium.Keys` value (`ENTER`, `TAB`,
`ESCAPE`, `ARROW_DOWN`, `SPACE`, ...) — either to a specific element (set
`locatorKey`) or to whatever currently has focus (leave `locatorKey` blank).
`TC03_KeyboardOnlyLogin` in the example script logs in using only `Tab` and
`Enter` — no `CLICK` on the form fields at all — which is the pattern to
copy for accessibility-style "can this be operated without a mouse" checks.

---

## 🔐 Keeping Credentials Out of Scripts (Placeholders)

`testData` and `expected` cells may contain placeholders, expanded when the step runs
(see `PlaceholderResolver`), so real usernames/passwords are never committed:

| Placeholder | Reads |
|---|---|
| `${env:NAME}` | OS environment variable `NAME`, falling back to `-DNAME=value` |
| `${sys:NAME}` | JVM system property only |
| `${config:key}` | ConfigReader key (`-D` → `<site>.properties` → `global.properties`) |
| `${i18n:key}` | translated text for `key` in the active language (`-Di18n.language=hi`, see `LanguageUtils`), so one script runs against the English/Hindi/Gujarati build of an app |
| `${env:NAME:-fallback}` | any form accepts an inline default after `:-` |

- A placeholder with no value and no default **fails that step** naming the missing variable —
  it is never silently replaced by an empty string.
- Only the raw placeholder text is logged; the resolved value never reaches `toString()`, the
  console, or Allure/Extent step names. `TYPE` into a locator key that looks sensitive
  (`password`, `token`, `otp`, …) is additionally routed through `typeSecret()`.
- Text such as `${jndi:...}` (no `env`/`sys`/`config`/`i18n` prefix) is left untouched, so
  injection-payload test data still works.
- **CSV tip:** a description or any cell containing a comma must be quoted
  (`"like, this"`); `CsvDataFileReader` now logs a warning for rows wider than the header.

SAHMAT uses `${env:SAHMAT_EMAIL}` / `${env:SAHMAT_PASSWORD}`:

```bash
export SAHMAT_EMAIL='...'  SAHMAT_PASSWORD='...'      # or: mvn test -DSAHMAT_EMAIL=... -DSAHMAT_PASSWORD=...
```

CI: GitHub Actions reads repo secrets `SAHMAT_EMAIL` / `SAHMAT_PASSWORD` (already wired in
`github-ci.yml`); GitLab CI exports project CI/CD variables of the same names automatically;
Jenkins needs them in the job/agent environment (e.g. `environment { SAHMAT_EMAIL = credentials('...') }`).

---

## 🔗 DDT Enhancements Used Alongside This

`DataProvider` (and therefore `KeywordReader`, since it reads through
`DataProvider`) now supports:

- **YAML** data files (`.yaml`/`.yml`), via the new `snakeyaml` dependency
- An **`execute` column** — set a row to `no`/`false`/`0`/`skip` to exclude
  it (and see it logged as skipped) without deleting it
- A **`tags` column** — comma/pipe separated; run a subset with
  `-Ddata.tags=smoke,regression`
- `DataProvider.readAll(path)` / `DataProviderFactory.fromFileUnfiltered(path)`
  to bypass both filters (e.g. a data-audit run)
- `DataProvider.readWithTags(path, "smoke")` /
  `DataProviderFactory.fromFileWithTags(path, "smoke")` to pick an explicit
  tag set regardless of the `-Ddata.tags` system property

> [!NOTE]
> See `login.yaml` and the updated `login.csv`/`login.json` for the column layout, and `LoginDataDrivenTest.verifyLoginFromYaml` / `.auditAllLoginRows` for usage.

<div align="center">

<sub>⬆️ <a href="#-keyword-driven-testing">Back to top</a> · <a href="README.md">← Back to README</a></sub>

</div>
