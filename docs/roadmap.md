<div align="center">

# 🗺️ Roadmap

*Ideas for where this framework could go next, roughly ordered by effort-to-value.*

<p>
  <img alt="Completed" src="https://img.shields.io/badge/Completed-15-2ea44f?style=flat-square">
  <img alt="Open" src="https://img.shields.io/badge/Still%20Open-4-D97706?style=flat-square">
</p>

</div>

---

## 📋 Table of Contents
- [✅ Completed](#-completed)
- [🔜 Still Open](#-still-open)

---

## ✅ Completed

> [!NOTE]
> Each entry links back to the doc or code that covers it, plus any "still open" nuance for that specific item — a checked box doesn't always mean *fully* done, just done enough to no longer be a blocker.

- [x] ~~**API-layer assertions alongside UI**~~ — done via `BookStoreApiTest` (Rest-Assured, see [🌐 Book Store REST API Tests](testing-guide.md#-book-store-rest-api-tests)). Next step here: have the UI flow *consume* the API for setup/teardown (seed a book via API before a UI test, verify cleanup via API after) rather than the two staying fully independent.
- [x] ~~**Visual regression**~~ — done via `core/utils/VisualRegressionUtils.java` (AShot pixel-diff, see `VisualRegressionTest.java` + `testng-suites/demoqa-visual.xml`). First run per snapshot captures the baseline; commit it, then it's a real regression check. Still open: only one demoqa page has a baseline so far — extend coverage to more pages as they stabilize.
- [x] ~~**Checkstyle/PMD enforcement in CI**~~ — done: `checkstyle.xml` + `maven-checkstyle-plugin` bound to `verify` (see [🧹 Code Quality — Checkstyle](reports-and-quality.md#-code-quality--checkstyle)). Now wired into all three CI pipelines via direct `checkstyle:check@checkstyle-check` execution invocation (not a full `mvn verify`, which would re-run the whole suite) — a violation marks the build UNSTABLE rather than hard-failing it.
- [x] ~~**Coverage threshold gate**~~ — done: `jacoco:check` execution in pom.xml enforces 50% line coverage on `com.automation.core.*`. Because every CI job/branch only exercises the slice of core/ its own suite touches, the real gate merges every job's `jacoco.exec` first (`jacoco:merge@jacoco-merge`) and checks the union — see the `coverage-gate` job (GitHub Actions/GitLab CI) / `Coverage Gate` stage (Jenkins).
- [x] ~~**Parameterize the GitHub Actions workflow**~~ — already done in `github-ci.yml`'s `workflow_dispatch.inputs` (suite_type/browser/headless/retry_count), matching what Jenkins exposes as build parameters.
- [x] ~~**Multi-browser matrix in CI**~~ — done across all three pipelines. GitHub Actions: `test` job's matrix cross-joins `site x browser` (chrome/firefox/edge) on every push/PR/schedule run, or just the one browser picked via `workflow_dispatch`. GitLab CI: `test` job's `parallel:matrix` now cross-joins `SITE x BROWSER` the same way (2 sites x 3 browsers = 6 instances), installing firefox/edge if missing on the runner the same way Chrome already was. Jenkins: opt-in via the `ALL_BROWSERS` boolean parameter (off by default to keep normal build runtime unchanged) — fans each site out across all three browsers when ticked.
- [x] ~~**Self-healing / fallback locators**~~ — originally just `core/utils/SmartLocator.java` (hand-picked fallbacks, opt-in per locator). Extended to a full automatic engine: `core/selfhealing/SelfHealingEngine.java` snapshots each element's identifying attributes on every successful find, and on a `TimeoutException` re-finds it by DOM-similarity scoring against that fingerprint instead of failing outright — with a confidence threshold, a persisted repository (`target/self-healing/locator-repository.json`) so healing works across runs, and an end-of-run `healing-report.json` so a drift that got silently healed still shows up. Wired into the framework's actual chokepoints — `BasePage.waitVisible/waitClickable`, `HumanActions.click/type` (the two most-used interaction methods), and `KeywordEngine` — so every page object gets it for free, not just `DatePickerPage`. `SmartLocator` still exists for the cases a developer wants an explicit, guaranteed fallback rather than a scored guess; its own primary-locator lookup now goes through the same engine. See [🩹 Self-Healing Locators](architecture.md#-self-healing-locators). Still open: the similarity scoring is attribute/text-based only (no visual/screenshot matching), and there's no dashboard over `healing-report.json` yet — it's just a JSON file to review or wire into CI as an artifact.
- [x] ~~**Safari support**~~ — done, but scoped to GitHub Actions only. `DriverFactory` supports `browser=safari` locally and via Grid/RemoteWebDriver, with the macOS-only/no-headless/one-session-at-a-time constraints documented on its class javadoc. Dedicated `testng-suites/<site>-safari-<suite>.xml` suites (`parallel="none"`) exist for every browser site. GitHub Actions' `test-safari` job (`macos-latest`) runs automatically on every push (plus on-demand via the `run_safari` manual-dispatch input) — see [🧭 Safari](ci-cd.md#-safari). Jenkins and GitLab CI deliberately don't run Safari at all: neither has a macOS agent/runner registered, and provisioning one is real infrastructure this framework can't stand up on its own — Jenkins' site-discovery stage filters the `-safari` suite files back out rather than fail on them. Still open: never run end-to-end against a real macOS agent outside GitHub's own hosted runner.
- [x] ~~**Secret-scanning in CI**~~ — done via `gitleaks` against the working tree, wired into all three pipelines: GitHub Actions' `secret-scan` job and GitLab's `secret-scan` job run on every push/PR, Jenkins' `Secret Scan` stage runs on every build. Report-only for now (`continue-on-error`/`allow_failure`/marks-UNSTABLE rather than hard-failing) — a repo's first-ever run commonly turns up pre-existing/false-positive matches in history or test fixtures that need triage before this can safely block pushes. Still open: run an initial full-history scan, triage the findings (add a `.gitleaksignore` baseline for confirmed false positives), then flip each pipeline to hard-fail on new findings.
- [x] ~~**OWASP dependency-vulnerability scanning in CI**~~ — the `security` Maven profile (OWASP Dependency-Check, see `pom.xml`) is now wired into all three pipelines as a nightly job/stage (`security-scan` in GitHub Actions/GitLab CI, `Security Scan (Nightly)` in Jenkins) rather than every push, since the first-ever run downloads/builds the NVD CVE database and is slow. Report-only by default (`failBuildOnCVSS`/`SECURITY_FAIL_CVSS` defaults to 11 = never fails) — overridable per pipeline (repo/org Variable in GitHub Actions, CI/CD variable in GitLab, build parameter in Jenkins) once the team is ready to gate merges on a CVSS threshold like 7. Still open: run it once for real and see what the current dependency tree actually reports before deciding on a threshold.
- [x] ~~**Automated dependency updates**~~ — already handled: Dependabot is active on this repo (see the `dependabot/maven/*` and `dependabot/github_actions/*` branches) and keeps Selenium, TestNG, Jackson, and the GitHub Actions themselves current automatically.
- [x] ~~**Accessibility (WCAG/GIGW-adjacent) scanning**~~ — done via `core/utils/AccessibilityUtils.java` (axe-core), see `AccessibilityTest.java` + `testng-suites/demoqa-accessibility.xml`, and [♿🖼️⏱️ Specialized Testing](testing-guide.md#️️-specialized-testing--accessibility-visual-regression--performance) above. Opt-in only (`accessibility` group, not wired into CI) — `a11y.failOn` starts at `critical,serious`; tighten once known issues on each page are triaged.
- [x] ~~**Keyword-driven testing beyond saucedemo**~~ — done: `KeywordDrivenTextBoxTest.java` + `demoqa_textbox_keywords.csv` + `objectrepository/demoqa.properties` port the existing engine to a demoqa flow — see [🧵 Keyword-Driven & Data-Driven Testing](testing-guide.md#-keyword-driven--data-driven-testing) above. Still open: only the Text Box page so far — extend to more demoqa flows as needed.
- [x] ~~**Mobile (Appium) support**~~ — `AppiumDriverFactory`, `BaseMobilePage`, `MobileBaseTest`, and a working example (`SettingsHomePage` + `SettingsHomeTest` against Android's built-in Settings app) under `com.automation.mobile`, plus `testng-suites/mobile-smoke.xml`/`mobile-regression.xml` — see [📱 Mobile Testing (Appium)](testing-guide.md#-mobile-testing-appium) above and `mobile/README.md`. Wired into all three CI pipelines (Jenkins, GitHub Actions, GitLab CI) and verified end-to-end against a real Genymotion emulator (Android 15/API 35); `mobile/README.md` also now has a real-device walkthrough (Samsung Galaxy S24, USB + wireless `adb`). Still open: only one screen is covered, and iOS (`IOSDriver`) hasn't been run against a real simulator/device — only the Android path above is confirmed live.
- [x] ~~**Lightweight performance smoke check**~~ — done via the opt-in `perf` Maven profile (`jmeter-maven-plugin` + `perf/basic-smoke.jmx`), run with `mvn verify -Pperf`. Response-time/response-code smoke check only, not a load/capacity test — not wired into CI by default.

---

## 🔜 Still Open

- [ ] **Parallel execution** — TestNG is already parallel-ready (`ThreadLocal<WebDriver>`); flipping `parallel="methods"`/`"classes"` in the suite XMLs plus a `thread-count` would cut regression runtime significantly, especially combined with the Docker Grid's multi-session nodes.
- [ ] **Coverage-gate parity check** — the merge/check approach (`coverage-gate` job/stage) is now implemented identically across all three pipelines; worth a real run on each to confirm the 50% `com.automation.core.*` threshold is actually achievable with current test coverage rather than immediately marking every build UNSTABLE — tune the threshold in pom.xml if so.
- [ ] **Contract test for the debug-dump pattern** — now that three page objects (`BookStoreApplicationPage`, `ProfilePage`, `CheckBoxPage`) each hand-roll a near-identical `dumpPageForDebugging` method, it's a good candidate to promote into `core/utils` as a shared utility so future page objects get it for free.
- [ ] **Build-verify the three new dependencies** — `axe-core`, `ashot`, and `appium-java-client` were added to `pom.xml` without network access to Maven Central in the environment that added them; run `mvn dependency:resolve` (or just `mvn test`) locally and bump versions if any have since moved.

---

<div align="center">

<sub>⬆️ <a href="#-roadmap">Back to top</a> · <a href="../README.md">← Back to README</a></sub>

</div>

