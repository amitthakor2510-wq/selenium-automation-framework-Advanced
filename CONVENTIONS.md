<div align="center">

# 🧭 Conventions Cheatsheet

*Quick "how do I do X here" reference for this project's own conventions.*

</div>

---

Quick reference for this project's own conventions — for when you're back in this codebase after a few weeks away and don't want to re-derive everything from scratch. Not a contributor guide (this is a solo project) — just the "how do I do X here" answers in one place.

> [!NOTE]
> For the full architecture writeup, see [docs/architecture.md](docs/architecture.md).
> For a worked example of every convention below in actual code, see
> [TemplatePage.java](src/main/java/com/automation/template/TemplatePage.java) +
> [TemplateTest.java](src/test/java/com/automation/template/TemplateTest.java) —
> they compile and get Checkstyle-checked like anything else, but live in a
> package no suite XML matches, so they never actually run.

## 📋 Table of Contents
- [🆕 Starting a New Site](#-starting-a-new-site)
- [📄 Writing a Page Object](#-writing-a-page-object)
- [🧪 Writing a Test](#-writing-a-test)
- [📝 Logging](#-logging)
- [⚙️ Config](#️-config)
- [📊 Reports](#-reports)
- [🔄 CI Pipelines](#-ci-pipelines)
- [✅ Before Committing](#-before-committing)

---

## 🆕 Starting a New Site

Don't hand-write the scaffolding — run:
```bash
./Scripts/new-site.sh <sitename> <base-url>
```
It sets up all three testing styles (standard Page Object, keyword-driven,
data-driven) at once. See [docs/extending.md](docs/extending.md) for what it
generates and why all three exist.

---

## 📄 Writing a Page Object

- Extend `BasePage`, call `super(driver)` and nothing else in the constructor.
- Every locator is a `private final By` field at the top of the class —
  never inline inside a method.
- Locator preference order: `By.id()` > `By.cssSelector()` > `By.xpath()`.
- Use `HumanActions.click()` / `HumanActions.type()` instead of calling
  `element.click()`/`.sendKeys()` directly — they wait, add a randomized
  human-like pause (config-driven, off entirely for CI via
  `-Dhuman.pause.enabled=false`), and show up as named Allure steps for free.
- Use `waitVisible()` / `waitClickable()` / `getText()` / `isDisplayed()`
  (all on `BasePage`) instead of raw `driver.findElement()`.

> [!WARNING]
> **Never** `Thread.sleep()` to wait for a page — wait for the actual
> condition instead. It either wastes time or races a slow page.

- If a locator is genuinely likely to break on a UI-library swap (real
  example: demoqa's Check Box widget went from `react-checkbox-tree` to
  `rc-tree` mid-project), use `SmartLocator.find()` with a fallback locator —
  see `DatePickerPage.java` for a real one. Don't reach for it by default.
- Page objects return data/booleans — they never call `Assert.*`. Keeps them
  reusable across regression, smoke, and keyword-driven CSV steps.

---

## 🧪 Writing a Test

- Extend `BaseTest`. Create your page object in `@BeforeMethod`, not as a
  field initializer — `getDriver()` isn't valid until `BaseTest.setUp()` has
  run first.
- Every `@Test` needs a `groups` attribute — `"smoke"` (fast, gates every
  PR) and/or `"regression"` (fuller suite, nightly).

> [!IMPORTANT]
> No `groups` attribute means the test never runs in any suite — silently.

- Retry is automatic (`RetryListener`, wired in each suite XML) — never
  reference `RetryAnalyzer` from a test class directly.
- Assertions use TestNG's `Assert`, not AssertJ/Hamcrest/etc. — nothing else
  is a dependency in this project.

---

## 📝 Logging

> [!WARNING]
> Every class gets its own `private static final Logger logger = Logger.getLogger(<ClassName>.class.getName());` — copy-paste the class
> name exactly, don't hand-type it. A mismatch doesn't error, it just
> silently misattributes every log line to the wrong class — a real bug
> found and fixed across 19 files in this codebase.
>
> **Never** `System.out.println` / `System.err.println`. Same 19-file bug.

---

## ⚙️ Config

- Site-specific values live in `src/test/resources/config/<site>.properties`
  — copy `_TEMPLATE.properties.example` to start a new one, or let
  `new-site.sh` do it. `ConfigReader.get("key")` checks a `-D` system
  property override first, then falls back to the properties file — so CI
  can override anything without touching a committed file.
- Shared/global defaults live in `global.properties`.

---

## 📊 Reports

- Allure results write to `target/allure-results/` (per-site subdirectories
  in CI — see any `-Dallure.results.directory=...` override in the pipeline
  files). Extent reports write to `target/extent-reports/<site>-index.html`.
- See [docs/reports-and-quality.md](docs/reports-and-quality.md) for how
  each CI pipeline publishes these.

---

## 🔄 CI Pipelines

Three pipelines (Jenkins, GitLab, GitHub Actions) all run the same suites —
see [docs/ci-cd.md](docs/ci-cd.md) for specifics per platform. The short
version: every commit runs `demoqa` + `saucedemo` + `mobile` regression/smoke;
`accessibility` + `visual` (demoqa-only) run nightly on a schedule, not on
every commit — they're slower and more flake-prone than the functional
suites.

---

## ✅ Before Committing

```bash
mvn clean compile
mvn checkstyle:check
```

> [!TIP]
> Both should pass clean. See [docs/troubleshooting.md](docs/troubleshooting.md)
> for the errors that have actually come up before and what fixed them.

<div align="center">

<sub>⬆️ <a href="#-conventions-cheatsheet">Back to top</a> · <a href="README.md">← Back to README</a></sub>

</div>
