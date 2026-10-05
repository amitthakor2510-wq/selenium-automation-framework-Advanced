---
title: "SmartLocator"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/utils/SmartLocator.java"
loc: 112
---

# SmartLocator

> [!abstract] Explicit, hand-picked fallback locators — `find(primary, alt…)` for elements that already broke once.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/utils/SmartLocator.java` · 112 LOC

Absorbs the breakage class where a third-party site swaps a widget implementation under the same visible UI (real case: demoqa Check Box going `react-checkbox-tree` → `rc-tree`). Its own primary lookup also goes through [[SelfHealingEngine]], so it is the *explicit* counterpart of the *automatic* healer. Use sparingly — `DatePickerPage` is the reference example.

## Connections

- **Depends on →** [[SelfHealingEngine]]
- **Used by ←** [[DemoQA]]
- **Related ↔** [[Self-Healing Locators]]
