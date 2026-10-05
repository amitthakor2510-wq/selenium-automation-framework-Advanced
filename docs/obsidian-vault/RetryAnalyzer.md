---
title: "RetryAnalyzer"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/listeners/RetryAnalyzer.java"
loc: 94
---

# RetryAnalyzer

> [!abstract] Retries failed tests up to `retry.count` — but never retries deterministic authoring failures.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/listeners/RetryAnalyzer.java` · 94 LOC

- `retry.count` default **2** (`global.properties`); CI pipelines pass `RETRY_COUNT=0` for speed.
- **Deterministic failure → no retry:** `ConfigException`, `DataFileException`, or a `KeywordExecutionException` with *no* Selenium `WebDriverException` underneath (bad keyword/locator key/row). Walks the whole cause chain.
- A `KeywordExecutionException` that merely *wraps* a Selenium timeout/stale element **is** retried (an earlier bug treated every keyword failure as deterministic, so SAHMAT scenarios never retried).
- `DriverInitializationException` is deliberately retryable (Grid node blip, driver download hiccup).
- `RetryListener` (an `IAnnotationTransformer`) attaches the analyzer to every `@Test`; it must be in suite XML. `RetryAnalyzerTest` (9 tests) exercises `isDeterministicFailure` with no TestNG state.

## Connections

- **Depends on →** [[ConfigReader]] · [[Framework Exceptions]]
- **Used by ←** [[TestListener]] · [[BaseTest]]
- **Related ↔** [[Test Types and Suites]] · [[Unit Tests Profile]]
