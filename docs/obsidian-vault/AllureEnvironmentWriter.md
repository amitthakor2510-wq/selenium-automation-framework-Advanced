---
title: "AllureEnvironmentWriter"
type: class
layer: report
tags: [fw/report, kind/class]
parent: "[[Reporting and Observability]]"
source: "core/report/AllureEnvironmentWriter.java"
loc: 179
---

# AllureEnvironmentWriter

> [!abstract] Writes the two Allure files the plugin doesn't generate: `environment.properties` and `categories.json`.

**Part of:** [[Reporting and Observability]]  ·  **Layer:** Reporting  ·  **Source:** `core/report/AllureEnvironmentWriter.java` · 179 LOC

Environment widget: site, browser, OS, Java version, retry count. Categories tab auto-buckets failures into Product Defects / Element-not-found / Timeouts / Driver issues / Skipped. Runs once per JVM; `reset()` clears the flag so a Jenkins loop over sites regenerates per-site values. (A past bug: `writeOnce()` never executed — moved into `@BeforeMethod`.)

## Connections

- **Used by ←** [[TestListener]]
- **Related ↔** [[Allure Report]]
