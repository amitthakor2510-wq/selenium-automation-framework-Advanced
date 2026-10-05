---
title: "Architecture and Test Lifecycle"
type: hub
layer: core
tags: [fw/core, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Architecture and Test Lifecycle

> [!abstract] How one test runs end to end: suite XML → config → browser → listener tracking → Page Object actions → assertion → retry / evidence → teardown → reports.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Core framework

```mermaid
flowchart TD
  A([mvn test]) --> B[Suite XML selects tests]
  B --> C[ConfigReader loads settings]
  C --> D[BaseTest.setUp opens browser via DriverFactory]
  D --> E[TestListener starts Extent + Allure tracking]
  E --> F[Test drives UI through Page Object]
  F --> G{Passed?}
  G -- no, retries left --> R[RetryAnalyzer re-runs]
  R --> F
  G -- no --> X[Screenshot + page source + console logs + AI RCA]
  G -- yes --> S[Pass screenshot]
  X --> T[BaseTest.tearDown closes browser]
  S --> T
  T --> U[Reports written: Extent, Allure, JaCoCo]
```

## Four phases

1. **Suite startup (once per run)** — `testng-suites/*.xml` picks tests; [[TestSelection]] drops anything switched off in [[test-config properties|test-config.properties]]; [[ConfigReader]] validates the site against [[SiteRegistry]] and loads layered config; the first test makes [[AllureEnvironmentWriter]] write environment + categories.
2. **Per-test setup** — [[BaseTest]] asks [[DriverFactory]] for a browser (local, Grid or Appium); [[TestListener]] starts tracking and optionally [[VideoRecorder]].
3. **Execution & verification** — the test calls Page Object methods ([[BasePage]], [[HumanActions]]) whose element lookups pass through [[SelfHealingEngine]]; an assertion decides pass/fail; [[RetryAnalyzer]] reruns non-deterministic failures; dependent tests are skipped.
4. **Teardown & reporting** — [[ScreenshotUtil]] always, [[FailureDiagnostics]] on failure, optional [[AiExceptionAnalyzer]]; browser closed; [[ExtentManager]] flushes; CI merges JaCoCo and builds Allure.

## Key invariants
- `ThreadLocal<WebDriver>` per test thread → safe `parallel="classes"`.
- `TestListener` is registered via `@Listeners` on `BaseTest`, **not** in suite XML (Allure ordering bug).
- `RetryListener` is the opposite: it must be in the suite XML because it is an `IAnnotationTransformer`.

## Connections

- **Depends on →** [[BaseTest]] · [[DriverFactory]] · [[ConfigReader]] · [[TestListener]] · [[BasePage]] · [[SelfHealingEngine]] · [[RetryAnalyzer]]
- **Related ↔** [[Test Execution Runtime]] · [[Core Framework]] · [[Reporting and Observability]]
