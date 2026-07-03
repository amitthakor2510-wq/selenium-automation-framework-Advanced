# Selenium Automation Framework

Java + Selenium + TestNG + Maven, with a Jenkins CI/CD pipeline, a custom
Extent HTML report, regression/smoke test groups, and a **multi-site**
project layout so new sites can be added without restructuring anything.

---

## 1. Project structure

```
selenium-automation-framework/
├── Jenkinsfile                          # CI/CD pipeline (auto-discovers sites)
├── pom.xml
├── testng-suites/                       # one suite file per site x type
│   ├── demoqa-smoke.xml
│   └── demoqa-regression.xml
└── src/test/
    ├── java/com/automation/
    │   ├── core/                        # SITE-AGNOSTIC framework code
    │   │   ├── base/BaseTest.java
    │   │   ├── config/ConfigReader.java
    │   │   ├── driver/DriverFactory.java
    │   │   ├── listeners/TestListener.java
    │   │   ├── report/ExtentManager.java
    │   │   └── utils/{HumanActions,ScreenshotUtil}.java
    │   └── sites/                       # ONE PACKAGE PER SITE PROJECT
    │       └── demoqa/
    │           ├── pages/
    │           └── tests/
    └── resources/config/
        ├── global.properties            # defaults shared by all sites
        ├── demoqa.properties            # demoqa overrides (mainly `url`)
        └── _TEMPLATE.properties.example # copy this for a new site
```

**Rule of thumb:** anything in `core/` is shared and should never mention a
specific website. Anything site-specific (locators, page objects, test
flows, URLs) lives under `sites/<siteName>/`.

---

## 2. Adding a new site project

Say you want to add a site called `mysite`. You do **not** touch any core
framework file:

1. **Config**: copy `src/test/resources/config/_TEMPLATE.properties.example`
   to `src/test/resources/config/mysite.properties`, set `url=` and any
   overrides.
2. **Pages**: create `src/test/java/com/automation/sites/mysite/pages/...`
3. **Tests**: create `src/test/java/com/automation/sites/mysite/tests/...`,
   extending `com.automation.core.base.BaseTest`, tagged with
   `@Test(groups = {"smoke"})` and/or `@Test(groups = {"regression"})`.
4. **Suite files**: copy `testng-suites/demoqa-regression.xml` and
   `demoqa-smoke.xml` to `mysite-regression.xml` / `mysite-smoke.xml`,
   changing only the `<package name="...">` line to
   `com.automation.sites.mysite.tests`.
5. Run it locally:
   ```
   mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-regression.xml
   ```
6. **That's it for Jenkins.** The pipeline auto-discovers any
   `testng-suites/*-regression.xml` / `*-smoke.xml` file and runs it as its
   own site - see `SITE=ALL` in section 4. No Jenkinsfile edit needed.

---

## 3. Running locally

```bash
# demoqa regression, visible Chrome
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml

# demoqa smoke only, headless Firefox
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml -Dbrowser=firefox -Dheadless=true
```

Reports/artifacts land in:
- `target/extent-reports/<site>-report.html` - custom HTML report
- `target/screenshots/` - failure screenshots
- `target/surefire-reports/` - raw TestNG/Surefire XML (JUnit-format, for CI)

---

## 4. Jenkins CI/CD pipeline

The `Jenkinsfile` is a declarative pipeline with 4 build parameters:

| Parameter    | Values                        | Purpose                                    |
|--------------|--------------------------------|---------------------------------------------|
| `SUITE_TYPE` | `regression` / `smoke`         | which suite family to run                  |
| `SITE`       | `ALL` or a site name (`demoqa`)| run every discovered site, or just one     |
| `BROWSER`    | `chrome` / `firefox` / `edge`  | forwarded to `DriverFactory`               |
| `HEADLESS`   | `true` / `false`               | headless recommended for CI agents         |

**How discovery works:** the pipeline lists
`testng-suites/*-${SUITE_TYPE}.xml`, strips the suffix, and treats each
remaining name as a site to run with `-Dsite=<name>`. Add a new site's
suite file and the very next Jenkins build will include it under `SITE=ALL`
- no pipeline changes required.

**Setup required in Jenkins (one-time):**
1. Manage Jenkins → Tools → add a JDK 17 installation named `JDK17`.
2. Manage Jenkins → Tools → add a Maven installation named `Maven3`.
3. Install the **HTML Publisher** plugin (renders the Extent report as a
   build tab).
4. Make sure Chrome/Firefox/Edge are installed on the agent if you ever run
   `HEADLESS=false`; for headless runs a browser binary is still required,
   just no display server.
5. Create a Pipeline job pointing at this repo's `Jenkinsfile` (or a
   Multibranch Pipeline, so PRs get tested automatically).

**Build result:** if any site fails, the build is marked `UNSTABLE` (not a
hard failure) so a broken third-party demo site doesn't block everything -
adjust the `post { unstable { ... } }` block if you want stricter behavior.

---

## 5. Human pause - how it actually works now

Previously, a "human pause" existed but was only applied **after a whole
test finished** (pass or fail) - individual clicks/typing inside a test had
no consistent delay, and several tests instead used hardcoded
`Thread.sleep(1500)` / `pause(1)` calls that ignored config entirely.

That's fixed by `com.automation.core.utils.HumanActions`, which is now the
single place pause behaviour lives:

- `HumanActions.click(driver, locator)` - waits for the element, pauses a
  random `human.pause.min`–`human.pause.max` ms, then clicks.
- `HumanActions.type(driver, locator, text)` - same wait/pause, then types
  **character by character** with a small random delay per keystroke
  (`human.pause.typing.min/max`), instead of an instant `sendKeys(...)`.
- `HumanActions.postTestPause()` - still called by `TestListener` after
  each test finishes, using its own `human.pause.postTest.min/max` window.

All demoqa page objects (`TextBoxPage`, `WebTablesPage`, `RadioButtonPage`,
`CheckBoxPage`) now go through `HumanActions` instead of raw
`WebElement.click()/sendKeys()` or `Thread.sleep(fixedNumber)`. The only
remaining raw `Thread.sleep` calls are ones tied to a specific known app
quirk (e.g. DemoQA's React table needing ~1s to re-filter after a search
keystroke) and are commented as such - they are app-stability waits, not a
substitute for human pause.

**Config keys** (in `global.properties`, overridable per site or via `-D`):

```properties
human.pause.enabled=true          # master on/off switch
human.pause.min=400
human.pause.max=1200
human.pause.postTest.min=500
human.pause.postTest.max=1500
human.pause.typing.min=40
human.pause.typing.max=120
```

Set `human.pause.enabled=false` (or `-Dhuman.pause.enabled=false` in
Jenkins) for a fast, pause-free smoke run when you just need a quick
signal.

---

## 6. Regression vs smoke

- `@Test(groups = {"smoke"})` - fast, critical-path checks.
- `@Test(groups = {"regression"})` - the fuller suite; a test can belong to
  both groups (see `TextBoxTest`).
- Each site gets its own `*-smoke.xml` and `*-regression.xml` TestNG suite
  file under `testng-suites/`, both wired to the shared
  `com.automation.core.listeners.TestListener`.

---

## 7. Browser/driver support

`DriverFactory` supports `chrome`, `firefox`, and `edge` via
`-Dbrowser=<name>`, each with a `-Dheadless=true` option, using
WebDriverManager so no manual driver binaries are needed.
