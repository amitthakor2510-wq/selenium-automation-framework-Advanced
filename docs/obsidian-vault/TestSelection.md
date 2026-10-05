---
title: "TestSelection"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/listeners/TestSelection.java"
loc: 256
---

# TestSelection

> [!abstract] Decides which test classes, methods or groups are switched off, from `test-config.properties` and `-D` overrides. Fails OPEN.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/listeners/TestSelection.java` · 256 LOC

Applied by `TestSelectionListener`, which sets `enabled=false` on each `@Test` annotation the file switches off — TestNG then never schedules it (not reported as skipped).

| Rule | Syntax |
|---|---|
| class | `test.LoginTest.enabled=false` |
| method | `test.Class#method.enabled=false` |
| package | `test.com.automation.sites.demoqa.tests.*.enabled=false` |
| group | `group.perf.enabled=false` |
| allow-list | `run.only=ClassA,ClassB` |
| overrides | `-Dtests.run.only=…` · `-Dtests.disabled=A,B#m` · `-Dtest.config.file=path` |

Precedence: **anything disabled stays disabled** — `run.only` only narrows. Limit: an inherited method is matched by the class that *declares* it. 16 unit tests in `TestSelectionTest`.

## Connections

- **Depends on →** [[test-config properties]]
- **Used by ←** [[Automation Console]]
- **Related ↔** [[pipeline-config properties]] · [[Test Types and Suites]]
