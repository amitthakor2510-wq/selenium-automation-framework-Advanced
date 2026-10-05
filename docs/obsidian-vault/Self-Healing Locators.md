---
title: "Self-Healing Locators"
type: hub
layer: healing
tags: [fw/healing, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Self-Healing Locators

> [!abstract] Automatic recovery of locators that used to work — DOM similarity → visual hash → AI pick — applied to every Page Object with zero edits.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Self-healing

```mermaid
flowchart TD
  A[find locator] --> B{found?}
  B -- yes --> C[snapshot ElementFingerprint<br/>into LocatorRepository]
  B -- TimeoutException --> D[load saved fingerprint]
  D --> E[Stage 1 DOM scoring<br/>≥ 0.55 ?]
  E -- yes --> OK([use healed element])
  E -- no --> F[Stage 2 VisualHasher<br/>opt-in]
  F -- clears blended threshold --> OK
  F -- no --> G[Stage 3 AiLocatorHealer<br/>opt-in, index-only]
  G -- confidence ≥ 0.6 --> OK
  G -- no --> FAIL([original failure])
  OK --> R[HealingEvent → healing-report.json]
```

**Entry points (all route to [[SelfHealingEngine]]):** `BasePage.waitVisible/waitClickable` · `HumanActions.click/type` · `KeywordEngine` · `SmartLocator` last resort.

**Cannot heal** a locator that has *never* succeeded (no fingerprint) — a typo in a brand-new locator still fails immediately.

## Connections

- **Depends on →** [[SelfHealingEngine]] · [[LocatorRepository]] · [[VisualHasher]] · [[AiLocatorHealer]] · [[Healing Report Output]]
- **Related ↔** [[SmartLocator]] · [[AI Features]] · [[global properties]]
