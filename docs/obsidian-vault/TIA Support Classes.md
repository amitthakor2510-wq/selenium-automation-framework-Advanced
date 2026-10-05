---
title: "TIA Support Classes"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/SourcePathResolver.java"
loc: 59
---

# TIA Support Classes

> [!abstract] Small value/helper types: `ChangedFile`, `ChangeType`, `ImpactReason`, `ImpactResult`, `SourcePathResolver`, `TestClassDetector`.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/SourcePathResolver.java` · 59 LOC

`SourcePathResolver` converts `.java` paths ↔ FQCNs; `TestClassDetector` finds every concrete (non-abstract) top-level class under `src/test/java` — the candidate set the closure is intersected with; `ChangeType` = how a file differs between refs. All covered by pure-logic JUnit tests (`SourcePathResolverTest` 7, `TestClassDetectorTest` 5).

## Connections

- **Used by ←** [[TestImpactAnalyzer]]
