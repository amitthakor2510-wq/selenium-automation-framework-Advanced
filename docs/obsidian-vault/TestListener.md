---
title: "TestListener"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/listeners/TestListener.java"
loc: 611
---

# TestListener

> [!abstract] The reporting brain: one listener that drives Extent *and* Allure, tags MDC, records video, attaches evidence and triggers optional AI root-cause analysis.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/listeners/TestListener.java` · 611 LOC

Implements **both** `ITestListener` (Extent bookkeeping) and `IInvokedMethodListener` (all Allure attachments/labels).

**Why two interfaces?** `AllureTestNg` is itself an `ITestListener` that opens/closes the Allure test case; TestNG does not guarantee firing order between two listeners of the *same* interface, so attachments were randomly dropped. `IInvokedMethodListener.afterInvocation()` has a guaranteed position (fires before any `ITestListener` notification), eliminating the race.

| Hook | What happens |
|---|---|
| `onStart` | writes `environment.properties` + `categories.json` once per JVM ([[AllureEnvironmentWriter]]) |
| `beforeInvocation` | tags severity (`smoke`→critical, else normal), MDC `test`, starts [[VideoRecorder]] |
| `onTestSuccess` | Extent green + Allure pass screenshot |
| `onTestFailure` / `onTestSkipped` | red/grey + screenshot ([[ScreenshotUtil]]), page source + console ([[FailureDiagnostics]]), failed URL, optional [[AiExceptionAnalyzer]] attachment, keeps or discards video |
| `onFinish` | flush Extent, reset singletons for next site |

Skipped tests get the same evidence as failures (most "skips" are dependency skips from an upstream failure). Flaky tests (failed then passed after retry) are tagged via [[RetryAnalyzer]]`.getCount()`.

## Connections

- **Depends on →** [[ExtentManager]] · [[AllureEnvironmentWriter]] · [[ScreenshotUtil]] · [[FailureDiagnostics]] · [[VideoRecorder]] · [[AiExceptionAnalyzer]] · [[RetryAnalyzer]]
- **Used by ←** [[BaseTest]] · [[MobileBaseTest]]
- **Related ↔** [[Reporting and Observability]] · [[Allure Report]] · [[Extent Report]]
