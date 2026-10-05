---
title: "Unit Tests Profile"
type: tool
layer: quality
tags: [fw/quality, kind/tool]
parent: "[[Quality Gates]]"
---

# Unit Tests Profile

> [!abstract] `-Punit-tests` — Failsafe + JUnit 5 for the pure-logic classes TestNG's Surefire never ran.

**Part of:** [[Quality Gates]]  ·  **Layer:** Quality gates

Covers `core/tia` (10 classes), `core/data` + readers, `core/keyword` (PlaceholderResolver), `core/config` (SiteRegistryConsistencyTest), `core/mock` (DemoQaBookStoreFakeTest), `core/utils` (SensitiveDataTest), `sites/listeners` (RetryAnalyzerTest, TestSelectionTest). A bug fix made this profile actually execute in CI (it previously never ran anywhere).

## Connections

- **Depends on →** [[RetryAnalyzer]] · [[TestSelection]] · [[DataRow]]
- **Related ↔** [[Test Impact Analysis]]
