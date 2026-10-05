---
title: "Test Execution Runtime"
type: hub
layer: runtime
tags: [fw/runtime, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Test Execution Runtime

> [!abstract] Everything under `src/test/.../sites/core` and `sites/listeners` that wraps each test: base classes, listeners, retry, selection, coverage capture.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Test runtime

| Concern | Class |
|---|---|
| Browser lifecycle per test | [[BaseTest]] · [[MobileBaseTest]] · [[BaseApiTest]] · [[KeywordTestBase]] · [[PerfTestBase]] |
| Reporting glue | [[TestListener]] (Extent + Allure + MDC + video + AI RCA) |
| Retry | [[RetryAnalyzer]] + `RetryListener` (annotation transformer) |
| Test on/off | [[TestSelection]] + `TestSelectionListener` |
| Coverage capture | `JacocoPerTestCoverageListener` + `AlterSuiteForCoverageMapListener` → [[Coverage Map Pipeline]] |
| Evidence | [[ScreenshotUtil]] · [[FailureDiagnostics]] · [[VideoRecorder]] · [[SensitiveData]] |

**Listener registration rules (learned the hard way)**
- `TestListener` → `@Listeners` on `BaseTest` (so it fires *before* Allure closes the test case).
- `RetryListener` → each suite XML `<listeners>` (annotation transformers must be known before `@Test` parsing).
- `TestSelectionListener`, `AlterSuiteForCoverageMapListener`, ReportPortal agent → auto-registered through `META-INF/services/org.testng.ITestNGListener`.

## Connections

- **Related ↔** [[Architecture and Test Lifecycle]] · [[Test Types and Suites]]
