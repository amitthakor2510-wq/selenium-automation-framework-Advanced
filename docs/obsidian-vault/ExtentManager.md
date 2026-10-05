---
title: "ExtentManager"
type: class
layer: report
tags: [fw/report, kind/class]
parent: "[[Reporting and Observability]]"
source: "core/report/ExtentManager.java"
loc: 124
---

# ExtentManager

> [!abstract] Singleton that builds one self-contained Extent HTML report per site/browser/suite.

**Part of:** [[Reporting and Observability]]  ·  **Layer:** Reporting  ·  **Source:** `core/report/ExtentManager.java` · 124 LOC

Path `target/extent-reports/<site>/<browser-or-mobile>/<suite>/index.html` (physically separate files). `setActiveSuiteName`, `getInstance`, `reset` (called by `TestListener.onFinish` so a second site in the same JVM gets a fresh report). Was once non-thread-safe — fixed.

## Connections

- **Used by ←** [[TestListener]]
- **Related ↔** [[Extent Report]]
