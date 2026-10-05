---
title: "ArtifactRetentionCleaner"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/retention/ArtifactRetentionCleaner.java"
loc: 203
---

# ArtifactRetentionCleaner

> [!abstract] Prunes `target/videos` down to the last N *runs* (reconstructed from timestamp gaps), not the last N files.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/retention/ArtifactRetentionCleaner.java` · 203 LOC

Runs are inferred: files <`--gap-minutes` (30) apart belong to one run; keep newest `--keep-runs` (5). Opt-in (`-Pprune-artifacts`, `mvn exec:java@prune-artifacts`, `Scripts/prune-artifacts.sh`, `--dry-run`). GitLab's `report` job prunes to 10 runs before mirroring videos into `public/`. Heuristic can split a slow run — safe direction to be wrong in.

## Connections

- **Used by ←** [[Maintenance Scripts]] · [[GitLab CI Pipeline]]
- **Related ↔** [[VideoRecorder]] · [[gh-pages Retention]]
