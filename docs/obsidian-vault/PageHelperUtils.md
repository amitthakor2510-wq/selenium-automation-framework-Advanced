---
title: "PageHelperUtils"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/utils/DebugDumpUtils.java"
loc: 61
---

# PageHelperUtils

> [!abstract] ElementUtils + DebugDumpUtils — small shared helpers used by BasePage and KeywordEngine.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/utils/DebugDumpUtils.java` · 61 LOC

- **ElementUtils** (94 LOC): element-state helpers shared by `BasePage` and `KeywordEngine`.
- **DebugDumpUtils** (61 LOC): writes the current page source to `target/debug-dumps/*.html` right before failing, so a locator timeout on a redesigned site can be diagnosed offline. Three hand-rolled copies were consolidated into this one (contract-tested).

## Connections

- **Used by ←** [[BasePage]] · [[KeywordEngine]]
- **Related ↔** [[Conventions and Troubleshooting]]
