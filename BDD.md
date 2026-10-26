# BDD / Cucumber

Gherkin feature files run through TestNG, so they use the same Surefire, Allure and Selenium Grid path as the rest of the framework.

## Layout
| Path | Purpose |
|------|---------|
| `src/test/resources/features/<site>/*.feature` | Scenarios (Gherkin) |
| `src/test/java/com/automation/bdd/steps/` | Step definitions - call page objects + `Verify`, no locators |
| `src/test/java/com/automation/bdd/hooks/Hooks.java` | Browser per scenario (tag `@ui`), failure screenshot, applies `Verify` soft-check failures |
| `src/test/java/com/automation/bdd/runner/CucumberRunnerTest.java` | TestNG entry point |
| `testng-suites/bdd.xml` | Generic suite that runs the runner for any site |

## Run
```
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/bdd.xml
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/bdd.xml -Dcucumber.filter.tags="@smoke"
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/bdd-mobile.xml   # needs Appium + one device/emulator; runs sequentially
```
Only scenarios tagged `@<site>` run, so every feature needs its site's exact name as a tag (`@saucedemo`, `@demoqa`, `@SAHMAT`...). `-Dcucumber.filter.tags` is AND-ed with it. The site must also be enabled in `pipeline-config.properties`.
Concurrency = `data-provider-thread-count` in the suite XML (1 = sequential). Keep it <= `SE_NODE_MAX_SESSIONS`.

## Scenario tags
| Tag | What Hooks does |
|-----|-----------------|
| `@ui` | Browser via `DriverFactory` (honours `-Dbrowser`, Grid, headless), opens the site `url` |
| `@mobile` | Appium session via `AppiumDriverFactory` |
| `@api` | No driver; resets config and calls `ApiClient.configure()` |

## One set of steps for every site and app (no Java)
`CommonSteps` + `ElementLocator` work for any web site, mobile app or API. A tester writes only a feature file; copy `features/_TEMPLATE.feature.example`.

**Elements** ("element" below) can be an inline locator - nothing to set up - or an object-repository key:
`css:#login` `xpath://a[@id='x']` `id:user-name` `name:q` `text:Sign in` `class:btn` `linktext:Home` `tag:h1`
mobile: `accessibility:Login` `uiselector:new UiSelector().text("OK")` `predicate:` `classchain:`
or a key from `objectrepository/<site>.properties` (site prefix optional: `login.username`).

| Web / mobile sentence | Does |
|-----------------------|------|
| `the user opens the home page` / `opens the page "/path"` | Navigate (web) |
| `the user clicks "element"` / `taps "element"` | Click / tap |
| `the user types "text" into "element"` | Type (human-paced on web) |
| `clears "element"`, `selects "x" from "element"`, `hovers over`, `scrolls to` | Other actions |
| `the user presses the "ENTER" key`, `waits for the page to load` | Keys, page-load wait (no fixed "wait N seconds" step - use `should see` / `should not see`, which poll until ready) |
| `the user swipes up` / `swipes down`, `navigates back` | Mobile gestures, back |
| `the user switches to the frame "element"` / `switches back to the main page`, `accepts / dismisses the alert` | Frames, alerts |
| `the user should see / should not see "element"` | Visibility |
| `"element" should have text "text"` | Text contains |
| `the url should contain "x"`, `the page title should contain "x"` | Page checks |

| API (`@api`) sentence | Does |
|-----------------------|------|
| `the user sets the header "Name" to "value"` | Header for later requests |
| `the user sends a GET / DELETE request to "/path"` | Request |
| `the user sends a POST / PUT / PATCH request to "/path" with body "json"` (or `with the body:` + a docstring) | Request with JSON |
| `the response status should be 200`, `the response body should contain "x"` | Checks |
| `the response field "data.id" should be "1"`, `... should not be empty` | JSON-path checks |
| `the response time should be under 500 ms` | Speed gate |

Use `${NAME}` placeholders for secrets (see KEYWORD_DRIVEN_TESTING.md). Write a site-specific step (page object) only for what these cannot express. Gherkin sentences are global: two step classes with the same sentence give an "ambiguous step" error.

## Adding a feature for a new site
1. Add `features/<site>/<name>.feature`, tagged with the scenario type (`@ui`/`@mobile`/`@api`) and the site name.
2. Reuse existing steps or add a class in `bdd/steps/` extending `StepBase`.
3. Put locators/waits in the site's page object under `src/main/.../pages`.
4. Step text is global across all sites. Two classes defining the same sentence make Cucumber fail with "ambiguous step", so name the site in site-specific steps ("the user is on the SauceDemo login page") or put shared sentences in one common class.

## Mobile (Appium) features
- Tag the feature `@mobile` plus your app's site name (`@mobile @myapp`), and put the `mobile.*` keys (`mobile.app.package`, `mobile.app.activity` or `mobile.app.path`, `mobile.device.name`...) in `config/<site>.properties`; run with `-Dsite=<site> -DsuiteXmlFile=testng-suites/bdd-mobile.xml`. The bundled Settings example uses the site name `mobile` (`config/mobile.properties`), so its feature carries only `@mobile`.
- Do not rely on the config alone to open the app: have the first step call `activateApp(<package>)` (see `MobileSettingsSteps`), otherwise empty `mobile.app.*` values leave the session on the home launcher.
- One emulator = one session, so `Hooks` serialises `@mobile` scenarios; use `bdd-mobile.xml` (thread count 1).
- Shared steps on native screens: `scrolls to` swipes until the element shows, `presses the "KEY" key` sends an Android key (`ENTER`, `BACK`, `HOME`, `ESCAPE` = back...), `waits for the page to load` is a no-op. `hovers over` and `selects ... from` are web-only and fail with a clear message on mobile (tap the spinner and its option instead).

## CI
`bdd-tests` runs `saucedemo` (@ui, headless Chrome) and `jsonplaceholder` (@api) on every build in GitLab CI, Jenkins (`BDD Tests` stage) and GitHub Actions, each gated by `pipeline-config.properties`, feeding Allure, JaCoCo and the test gate. `@mobile` scenarios are not run in CI (they need the emulator `mobile-test` provisions) - run them locally.

## Reports
Allure and `target/cucumber-reports/cucumber.json` are produced. So is the Extent HTML report (`target/extent-reports/<site>/<browser>/<suite>/index.html`, mobile: `target/extent-reports/mobile/<suite>/`): `CucumberRunnerTest` attaches `TestListener` with `@Listeners`, exactly like `BaseTest`/`BaseApiTest`, and each scenario gets an entry named after the scenario (feature in the description, category `bdd`).
