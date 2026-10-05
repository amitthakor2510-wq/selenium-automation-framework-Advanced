---
title: "SelfHealingEngine"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/SelfHealingEngine.java"
loc: 616
---

# SelfHealingEngine

> [!abstract] Drop-in replacement for `wait.until(visibility/clickable)` that heals instead of failing — DOM scoring with optional visual and AI stages.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/SelfHealingEngine.java` · 616 LOC

**DOM-stage score weights**

| Signal | Weight |
|---|---|
| `id` exact | 30% |
| `name` exact | 20% |
| class-list Jaccard | 20% |
| visible-text similarity (normalized edit distance) | 15% |
| tracked attrs (`type, placeholder, aria-label, role, href, title, data-testid`) | 10% |
| same parent tag | 5% |

Accept if best ≥ `self-healing.threshold` (**0.55**) — no screenshots taken. Else, if `self-healing.visual.enabled`, [[VisualHasher]] screenshots a small candidate pool (or, when the *tag itself* changed, a broad scan of interactive elements) and blends dHash similarity by `self-healing.visual.weight` (0.5). Else, if `self-healing.ai.enabled`, [[AiLocatorHealer]].

Visual is off by default because the baseline hash is captured on **every successful find**. Master switch `self-healing.enabled=true` (set false to tell drift from a genuine break). 616 LOC; also exposes `elementKey` (page URL + locator).

## Connections

- **Depends on →** [[LocatorRepository]] · [[VisualHasher]] · [[AiLocatorHealer]] · [[Healing Report Output]] · [[ConfigReader]]
- **Used by ←** [[BasePage]] · [[HumanActions]] · [[KeywordEngine]] · [[SmartLocator]]
- **Related ↔** [[Self-Healing Locators]]
