---
title: "DataProvider"
type: class
layer: data
tags: [fw/data, kind/class]
parent: "[[Data-Driven Testing]]"
source: "core/data/DataProvider.java"
loc: 249
---

# DataProvider

> [!abstract] TestNG-facing `@DataProvider` source — reads any supported file, applies tag/execute filters; `DataProviderFactory` hands back ready `Object[][]`.

**Part of:** [[Data-Driven Testing]]  ·  **Layer:** Data-driven  ·  **Source:** `core/data/DataProvider.java` · 249 LOC

`DataProvider` (249 LOC) turns a path into rows; `DataProviderFactory` (85 LOC) exposes `fromFile`, `fromSheet`, `fromFileUnfiltered`, `fromFileWithTags(path, tags…)`, `syntheticRegistrations()` / `(count)` and `syntheticRegistrationEdgeCases()`. Skipped rows are logged, not silently dropped. Throws [[Framework Exceptions]] `DataFileException` on missing/malformed input (which [[RetryAnalyzer]] treats as non-retryable).

## Connections

- **Depends on →** [[Data File Readers]] · [[DataRow]] · [[global properties]]
- **Used by ←** [[KeywordReader]] · [[SauceDemo]] · [[DemoQA]]
- **Related ↔** [[SyntheticDataGenerator]]
