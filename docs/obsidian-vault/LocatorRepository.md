---
title: "LocatorRepository"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/LocatorRepository.java"
loc: 196
---

# LocatorRepository

> [!abstract] Persistent store of known-good `ElementFingerprint`s keyed by page URL + locator — survives between runs.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/LocatorRepository.java` · 196 LOC

JSON at `self-healing-data/locator-repository.json` — **deliberately outside `target/`** because every CI job runs `mvn clean` (an earlier path inside `target/` meant healing only ever worked within one run). Jenkins has a *Restore Self-Healing Cache* stage for the same reason.

**ElementFingerprint** (47 LOC) = tag, `id`, `name`, class list, visible text, tracked attributes (`role`, `aria-label`, `data-testid`, …), parent tag, and an optional screenshot hash.

## Connections

- **Used by ←** [[SelfHealingEngine]]
- **Related ↔** [[Jenkins Pipeline]] · [[ElementFingerprint]]
