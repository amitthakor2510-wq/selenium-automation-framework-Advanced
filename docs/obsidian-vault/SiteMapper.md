---
title: "SiteMapper"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/SiteMapper.java"
loc: 83
---

# SiteMapper

> [!abstract] Site key ↔ test package mapping and site inference from resource paths (`config/<site>.properties`, `objectrepository/<site>.properties`).

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/SiteMapper.java` · 83 LOC

Mirrors `SiteRegistry.KNOWN_SITES` as a tiny dependency-free list (update both together — [[new-site script|new-site.sh]] does). Constructed-path resources can't be traced precisely, so a change impacts **the whole site's** tests — coarser but still far narrower than a full run.

## Connections

- **Used by ←** [[TestImpactAnalyzer]] · [[TIA Reports and CLI]]
- **Related ↔** [[SiteRegistry]]
