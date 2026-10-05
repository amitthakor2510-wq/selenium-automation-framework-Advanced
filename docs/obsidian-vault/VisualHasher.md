---
title: "VisualHasher"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/VisualHasher.java"
loc: 120
---

# VisualHasher

> [!abstract] Perceptual difference-hash (dHash) of an element screenshot — recovers icon-only buttons and elements whose tag changed.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/VisualHasher.java` · 120 LOC

Stage 2 of healing. Opt-in (`self-healing.visual.enabled`). Recovers cases DOM scoring structurally cannot (e.g. `<button>` → `<div role="button">`). Cost: one extra element screenshot per successful find.

## Connections

- **Depends on →** [[ElementFingerprint]]
- **Used by ←** [[SelfHealingEngine]]
- **Related ↔** [[VisualRegressionUtils]]
