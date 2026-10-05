---
title: "VisualRegressionUtils"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/utils/VisualRegressionUtils.java"
loc: 143
---

# VisualRegressionUtils

> [!abstract] AShot pixel-diff against committed baselines — catches layout/CSS regressions locators cannot.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/utils/VisualRegressionUtils.java` · 143 LOC

`compareOrCaptureBaseline(driver, "demoqa", "text-box-page")` — first run saves the baseline and passes; later runs diff.

| Key | Default |
|---|---|
| `visual.enabled` | `true` |
| `visual.failOnDiff` | `true` |
| `visual.diffThreshold` | `0` pixels |

Baselines: `src/test/resources/visual-baselines/<site>/<name>.png` (commit them). Diffs: `target/visual-diffs/<name>-diff.png`, attached to Allure. Group `visual`, suite `demoqa-visual.xml`.

## Connections

- **Used by ←** [[DemoQA]]
- **Related ↔** [[Test Types and Suites]]
