---
title: "KeywordReader"
type: class
layer: keyword
tags: [fw/keyword, kind/class]
parent: "[[Keyword-Driven Testing]]"
source: "core/keyword/KeywordReader.java"
loc: 66
---

# KeywordReader

> [!abstract] Loads a script (any format DataProvider reads) and groups rows into ordered `KeywordStep` lists per `testCase`. Includes the `KeywordStep` row model.

**Part of:** [[Keyword-Driven Testing]]  ·  **Layer:** Keyword-driven  ·  **Source:** `core/keyword/KeywordReader.java` · 66 LOC

`readAll(path)` → `LinkedHashMap<testCase, List<KeywordStep>>` (file order preserved — SAHMAT's `@DataProvider` streams its key set) and `readTestCase(path, testCase)`. **KeywordStep** = one row: `testCase, stepNo, keyword, locatorKey, testData, expected, description`; malformed rows raise `KeywordExecutionException`.

## Connections

- **Depends on →** [[DataProvider]]
- **Used by ←** [[KeywordTestBase]] · [[SAHMAT]]
- **Related ↔** [[KeywordEngine]]
