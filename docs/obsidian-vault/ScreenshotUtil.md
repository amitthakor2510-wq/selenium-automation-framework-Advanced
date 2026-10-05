---
title: "ScreenshotUtil"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "core/utils/ScreenshotUtil.java"
loc: 117
---

# ScreenshotUtil

> [!abstract] Captures PNG screenshots on pass/fail — collision-proof file names, bytes and base64 for embedding.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `core/utils/ScreenshotUtil.java` · 117 LOC

Path `target/screenshots/<test>_<yyyyMMdd_HHmmssSSS>_<hex>.png`. Millisecond timestamp + random suffix + `REPLACE_EXISTING` fixed a bug where two same-second screenshots silently collided. Called only by [[TestListener]] (and the `SCREENSHOT` keyword) — never by tests.

## Connections

- **Used by ←** [[TestListener]] · [[KeywordEngine]]
- **Related ↔** [[FailureDiagnostics]] · [[Reporting and Observability]]
