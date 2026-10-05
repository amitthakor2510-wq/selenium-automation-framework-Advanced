---
title: "AccessibilityUtils"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/utils/AccessibilityUtils.java"
loc: 158
---

# AccessibilityUtils

> [!abstract] axe-core wrapper for WCAG / GIGW-adjacent accessibility scans on any page.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/utils/AccessibilityUtils.java` · 158 LOC

`AccessibilityUtils.assertNoViolations(driver, "TextBox page")`. Violations bucketed `critical | serious | moderate | minor`.

| Key | Default |
|---|---|
| `a11y.enabled` | `true` |
| `a11y.failOn` | `critical,serious` (set `none` for log-only rollout) |

Built because the day-job targets government portals under GIGW. Known demoqa markup violations are suppressed. Reused directly by [[SiteCrawler]]. Opt-in `accessibility` group via `demoqa-accessibility.xml` (nightly).

## Connections

- **Used by ←** [[SiteCrawler]] · [[DemoQA]]
- **Related ↔** [[Test Types and Suites]] · [[Quality Gates]]
