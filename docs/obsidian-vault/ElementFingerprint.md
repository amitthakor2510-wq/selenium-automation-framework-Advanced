---
title: "ElementFingerprint"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/ElementFingerprint.java"
loc: 47
---

# ElementFingerprint

> [!abstract] Lightweight snapshot of the attributes that made an element identifiable, captured at the moment a locator succeeds.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/ElementFingerprint.java` · 47 LOC

Fields: tag, id, name, classes, text, tracked attributes (type/placeholder/aria-label/role/href/title/data-testid), parent tag, optional visual hash. Stored by [[LocatorRepository]]; compared by [[SelfHealingEngine]] using the weights in that note.

## Connections

- **Used by ←** [[LocatorRepository]] · [[SelfHealingEngine]] · [[VisualHasher]]
