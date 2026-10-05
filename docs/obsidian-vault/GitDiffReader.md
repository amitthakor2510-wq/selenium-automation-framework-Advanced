---
title: "GitDiffReader"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/GitDiffReader.java"
loc: 148
---

# GitDiffReader

> [!abstract] Wraps `git diff --name-status` plus `git ls-files --others --exclude-standard` (brand-new files `git diff` never shows).

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/GitDiffReader.java` · 148 LOC

Produces normalized `ChangedFile` entries (repo-relative, forward-slash paths regardless of OS). Tested by `GitDiffReaderTest`.

## Connections

- **Used by ←** [[TestImpactAnalyzer]]
