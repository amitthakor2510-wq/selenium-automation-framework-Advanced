---
title: "FailureDiagnostics"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "core/utils/FailureDiagnostics.java"
loc: 69
---

# FailureDiagnostics

> [!abstract] Best-effort forensics on failure only: full page HTML and Chromium console logs.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `core/utils/FailureDiagnostics.java` · 69 LOC

`capturePageSource` and `captureBrowserConsoleLogs` (Chromium only, via `goog:loggingPrefs`). Both swallow their own exceptions — a crashed browser or Firefox without a console endpoint must never turn a real failure into a secondary NPE inside the listener. Output is attached to Allure and fed to [[AiExceptionAnalyzer]] when enabled.

## Connections

- **Used by ←** [[TestListener]] · [[AiExceptionAnalyzer]]
- **Related ↔** [[ScreenshotUtil]] · [[DriverFactory]]
