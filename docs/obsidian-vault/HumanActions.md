---
title: "HumanActions"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/utils/HumanActions.java"
loc: 228
---

# HumanActions

> [!abstract] Centralised human-like pacing — every click/type gets a randomized pause and shows up as an Allure `@Step`.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/utils/HumanActions.java` · 228 LOC

`pause()`, `postTestPause()`, `microPause()`, `click()`, `type()`, `typeSecret()`, `typeHumanLike()`, `hover()`.

| Key | Default |
|---|---|
| `human.pause.enabled` | `true` (CI: `-Dhuman.pause.enabled=false`) |
| `human.pause.min/max` | 100 / 300 ms between actions |
| `human.pause.postTest.min/max` | 150 / 400 ms after a test |
| `human.pause.typing.min/max` | 10 / 30 ms per keystroke chunk |

`click/type` are `@Step`-annotated, so **218 call sites across 32 Page Objects** become timestamped Allure steps with zero per-page edits. Both route through [[SelfHealingEngine]]. Secrets typed are masked via [[SensitiveData]]; `typeSecret()` masks unconditionally.

## Connections

- **Depends on →** [[SelfHealingEngine]] · [[SensitiveData]] · [[ConfigReader]]
- **Used by ←** [[BasePage]] · [[DemoQA]] · [[SauceDemo]]
- **Related ↔** [[Allure Report]]
