<div align="center">

# 🚀 Running Everything
### Master Guide

*One place for every way to run this framework, and where the results land afterward.*

</div>

---

For narrative "why" explanations, see the linked deep-dive docs — this file is commands only.

## 📋 Table of Contents
- [✅ Prerequisites](#-prerequisites)
- [🖥️ Local — Browser Tests](#️-local--browser-tests)
- [🐳 Docker — Selenium Grid](#-docker--selenium-grid)
- [🧵 Keyword-Driven & Data-Driven Tests](#-keyword-driven--data-driven-tests)
- [🌐 REST API Tests](#-rest-api-tests)
- [♿🖼️⏱️ Accessibility, Visual Regression & Performance](#️️-accessibility-visual-regression--performance)
- [📱 Mobile (Appium)](#-mobile-appium)
- [📊 Viewing Reports](#-viewing-reports)
- [📊 Code Coverage & Code Quality](#-code-coverage--code-quality)
- [🔄 CI/CD](#-cicd)
- [🧾 Command Cheat Sheet](#-command-cheat-sheet)

---

## ✅ Prerequisites

- Java 17 — `java -version`
- Maven 3.9+ — `mvn -version` — **or skip installing Maven entirely and use
  the bundled wrapper**: `./mvnw -version` (Windows: `mvnw.cmd -version`).
  The wrapper downloads the exact pinned Maven version on first run and
  every `mvn ...` command below works identically as `./mvnw ...` — this is
  what CI itself should move to for fully reproducible builds. See
  [`.mvn/wrapper/maven-wrapper.properties`](.mvn/wrapper/maven-wrapper.properties)
  for the pinned version.
- Chrome/Firefox/Edge installed locally **or** Docker (see below) — you don't need both
- Docker + Compose plugin — `docker compose version` (only for the Docker path)
- Node.js + `npm install -g appium` — only for the mobile path

---

## 🖥️ Local — Browser Tests

```bash
# Smoke suite — fastest sanity check (Chrome, visible browser)
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# Full regression suite
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml

# Single test class only
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dtest=ButtonsTest

# Different browser
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dbrowser=firefox

# Headless — no visible browser window
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dheadless=true

# saucedemo instead of demoqa
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-smoke.xml

# Slow mode — watch every action clearly
mvn test -Dhuman.pause.min=2000 -Dhuman.pause.max=3000 -Dtest=ButtonsTest

# Fast mode — disable all pacing pauses
mvn test -Dhuman.pause.enabled=false -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# Disable retry — see a failure immediately instead of it re-running
mvn test -Dretry.count=0 -Dtest=BookStoreApplicationTest
```

> [!TIP]
> Any `global.properties` key can be overridden the same way with `-Dkey=value`. Full key reference: [`docs/configuration.md`](docs/configuration.md).

---

## 🐳 Docker — Selenium Grid

No local browser install needed — runs against a containerized Chrome/Firefox/Edge grid, with a live view via noVNC.

```bash
# 1. Start the grid (hub + all three browser nodes) — leave running across multiple test runs
docker compose up -d selenium-hub chrome firefox edge

# 2. Confirm it's healthy
docker compose ps

# 3. Run the default suite (demoqa smoke, Chrome, headless)
docker compose run --rm tests

# 4. Run a different browser/site/suite
docker compose run --rm \
  -e BROWSER=firefox \
  -e SITE=saucedemo \
  -e SUITE=testng-suites/saucedemo-regression.xml \
  tests

# 5. Watch a run live — turn OFF headless mode so the node actually renders something,
#    then open the matching noVNC URL below (password: secret) BEFORE/while the run starts
docker compose run --rm -e HEADLESS=false tests
open http://localhost:7900        # chrome — firefox: 7901, edge: 7902

# 6. Grid console (session/node status)
open http://localhost:4444/ui/    # note the trailing slash — without it, assets 404

# 7. Tear everything down
docker compose down -v
```

Reports land back on your host under `target/` via volume mounts (`allure-results`, `extent-reports`, `surefire-reports`, `debug-dumps`, `screenshots`) exactly as they would from a local `mvn test` — see [Viewing Reports](#-viewing-reports) below. Full detail on how the wiring works: [`DOCKER.md`](DOCKER.md).

**Single container against an existing/remote grid (no Compose):**
```bash
docker build -t selenium-framework .
docker run --rm \
  -e GRID_URL=http://<your-grid-host>:4444/wd/hub \
  -e SITE=demoqa -e BROWSER=chrome \
  selenium-framework
```

---

## 🧵 Keyword-Driven & Data-Driven Tests

```bash
# Keyword-driven demoqa Text Box (CSV-scripted steps)
mvn test -Dtest=KeywordDrivenTextBoxTest

# Keyword-driven saucedemo login
mvn test -Dtest=KeywordDrivenLoginTest

# Data-driven saucedemo login (same test, 5 interchangeable file formats: csv/json/xlsx/yaml/zip)
mvn test -Dtest=LoginDataDrivenTest
```
CSV schema, locator object-repository format, and how to add a new scenario without touching Java: [`KEYWORD_DRIVEN_TESTING.md`](KEYWORD_DRIVEN_TESTING.md).

---

## 🌐 REST API Tests

Pure-HTTP Book Store flow via Rest-Assured — no browser, independent of every other test class.
```bash
mvn test -Dtest=BookStoreApiTest
```

---

## ♿🖼️⏱️ Accessibility, Visual Regression & Performance

Three opt-in test types, none run in CI by default:

```bash
# Accessibility — axe-core WCAG/GIGW-adjacent scan
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml

# Visual regression — AShot pixel-diff vs. a committed baseline
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-visual.xml

# Performance smoke — JMeter response-time/response-code check (not a load test)
mvn verify -Pperf
# tune concurrency/thresholds:
mvn verify -Pperf -Dthreads=10 -DrampUp=5 -Dloops=5 -DmaxResponseMs=3000
```
JMeter results: `target/jmeter/results/` (raw) and `target/jmeter/reports/` (HTML). Details on all three: [`docs/testing-guide.md`](docs/testing-guide.md#️️-specialized-testing--accessibility-visual-regression--performance).

---

## 📱 Mobile (Appium)

```bash
# 1. Start the Appium server (separately installed — npm install -g appium)
appium

# 2. Copy the example config and fill in real values
cp src/test/resources/config/mobile.properties.example \
   src/test/resources/config/mobile.properties
# edit mobile.device.name to match `adb devices`

# 3. Run it — same -Dsite mechanism as every other site
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
```
Full setup (including remote/cloud grids like BrowserStack/Sauce Labs): [`src/main/java/com/automation/mobile/README.md`](src/main/java/com/automation/mobile/README.md).

---

## 📊 Viewing Reports

```bash
# Extent — self-contained HTML, open directly
open target/extent-reports/demoqa-report.html        # macOS
xdg-open target/extent-reports/demoqa-report.html     # Linux

# Allure — interactive, with step-by-step timelines and history
allure serve target/allure-results
# or, for a static folder to host/archive:
mvn allure:report                                      # writes target/allure-report/
```
What each report shows, and what gets attached on pass/fail/skip: [`docs/reports-and-quality.md`](docs/reports-and-quality.md#-test-reports).

---

## 📊 Code Coverage & Code Quality

```bash
# Coverage — produced by every `mvn test`, no separate command needed
mvn test
open target/site/jacoco/index.html

# Checkstyle — opt-in, NOT run by `mvn test`
mvn verify
```

---

## 🔄 CI/CD

Three pipelines are included and runnable as-is — Jenkins, GitHub Actions, GitLab CI — each covering browser sites, mobile, and a nightly accessibility/visual suite. One-time setup and build parameters: [`docs/ci-cd.md`](docs/ci-cd.md).

---

## 🧾 Command Cheat Sheet

| Want to... | Command |
|---|---|
| Fastest local sanity check | `mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml` |
| Full local regression | `mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml` |
| One test class | `mvn test -Dtest=ButtonsTest` |
| No local browser install | `docker compose up -d selenium-hub chrome firefox edge && docker compose run --rm tests` |
| Watch tests live in Docker | `docker compose run --rm -e HEADLESS=false tests` then open `localhost:7900` |
| Keyword-driven | `mvn test -Dtest=KeywordDrivenTextBoxTest` |
| API only | `mvn test -Dtest=BookStoreApiTest` |
| Accessibility scan | `mvn test -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml` |
| Visual regression | `mvn test -DsuiteXmlFile=testng-suites/demoqa-visual.xml` |
| Performance smoke | `mvn verify -Pperf` |
| Mobile | `mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml` |
| Open Allure report | `allure serve target/allure-results` |
| Coverage report | `mvn test` then open `target/site/jacoco/index.html` |
| Style/quality gate | `mvn verify` |

<div align="center">

<sub>⬆️ <a href="#-running-everything">Back to top</a> · <a href="README.md">← Back to README</a></sub>

</div>
