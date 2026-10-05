---
title: "DriverFactory"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/driver/DriverFactory.java"
loc: 1375
---

# DriverFactory

> [!abstract] The single place that creates browsers — local Chrome/Firefox/Edge/Brave/Safari or a remote Grid session — hardened for parallel CI.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/driver/DriverFactory.java` · 1375 LOC

Second-largest file (1,375 LOC). Registry of `BrowserProvider`s keyed `chrome | firefox | edge | brave | safari`.

**Capabilities**
- `headless=true|false`; Edge/Firefox use `PageLoadStrategy.EAGER`.
- `GRID_ENABLED=true` → `RemoteWebDriver` against `grid.url` (default `http://localhost:4444/wd/hub`); `LocalFileDetector` for uploads; warns that Grid headless is node-controlled.
- Download folder `target/downloads` (works locally and on CI).
- Chromium: `goog:loggingPrefs` for console capture (consumed by [[FailureDiagnostics]]); verbose chromedriver logs `target/logs/chromedriver-<browser>.log`.
- **Parallel-safe**: per-thread temp profile dirs, port allocation with retry-with-jitter (up to 12 attempts), `createWithPortConflictRetry`.
- `forceKillOrphanedDriverProcess` uses `goog:processID` + `ProcessHandle` to kill leaked chromedriver.
- **Brave**: needs `-Dbrave.binary=` or auto-detect; fix for SIGTRAP crash = `excludeSwitches: ["test-type"]`.
- **Safari**: macOS only, `safaridriver --enable` once, **no headless** (warns and continues), one session per machine → use the `*-safari-*.xml` suites (`parallel="none"`).
- Public API: `createDriver`, `quitDriver`, `forceKillOrphanedDriverProcess`, `getDownloadPath`.

## Connections

- **Depends on →** [[ConfigReader]]
- **Used by ←** [[BaseTest]] · [[Selenium Grid]]
- **Related ↔** [[AppiumDriverFactory]] · [[Docker and Selenium Grid]]
