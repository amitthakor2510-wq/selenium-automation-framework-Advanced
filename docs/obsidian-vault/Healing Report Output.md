---
title: "Healing Report Output"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/SelfHealingReportWriter.java"
loc: 44
---

# Healing Report Output

> [!abstract] `HealingEvent` + `SelfHealingReportWriter` → `target/self-healing/healing-report.json`, plus CI aggregation.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/SelfHealingReportWriter.java` · 44 LOC

Written only if at least one heal happened: flat list `{elementKey, originalLocator, healedDescription, score, matchMethod (dom|visual|ai), timestamp}`. CI jobs write `<site>-healing-report.json` via `-Dself-healing.report.path=…`; `compute_self_healing_summary.py` surfaces them on PRs and the landing page, and the [[Automation Console]] lists them as *Self-healed locators*. A locator that heals repeatedly should be **fixed at source**.

## Connections

- **Used by ←** [[SelfHealingEngine]] · [[CI Python Scripts]] · [[Automation Console]]
- **Related ↔** [[Reporting and Observability]]
