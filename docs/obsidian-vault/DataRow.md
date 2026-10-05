---
title: "DataRow"
type: class
layer: data
tags: [fw/data, kind/class]
parent: "[[Data-Driven Testing]]"
source: "core/data/DataRow.java"
loc: 98
---

# DataRow

> [!abstract] One row of test data as a typed, column-addressable object — tests never care which file format produced it.

**Part of:** [[Data-Driven Testing]]  ·  **Layer:** Data-driven  ·  **Source:** `core/data/DataRow.java` · 98 LOC

API: `get(col)`, `getRequired(col)` (missing column → `DataFileException`), `has(col)`, `getRowIndex()`, `toMap()`, and `DataRow.preservingWhitespace(...)` for cells where leading/trailing spaces matter. `DataRowTest` is a 14-test pure-logic suite (no WebDriver, no file I/O).

## Connections

- **Used by ←** [[DataProvider]] · [[SyntheticDataGenerator]] · [[KeywordReader]]
- **Related ↔** [[Unit Tests Profile]]
