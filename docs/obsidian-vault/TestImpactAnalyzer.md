---
title: "TestImpactAnalyzer"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/TestImpactAnalyzer.java"
loc: 215
---

# TestImpactAnalyzer

> [!abstract] Orchestrator: git diff → classify → either FULL or walk the dependency graph + resource/site fallbacks → impacted test set.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/TestImpactAnalyzer.java` · 215 LOC

`analyze(...)` overloads produce an `ImpactResult` (with `ImpactReason` per test for auditability). A changed **test-source** class seeds the closure just like main source — important because `BaseTest`, `BaseApiTest`, `KeywordTestBase`, `MobileBaseTest` live under `src/test` (regression test `baseSourceChangePropagatesToSubclassesEvenWhenBaseIsUnderTestRoot`).

## Connections

- **Depends on →** [[GitDiffReader]] · [[DependencyGraph]] · [[UnsafeChangeRules]] · [[ResourceReferenceIndex]] · [[SiteMapper]] · [[Coverage Map Pipeline]] · [[TIA Support Classes]]
- **Used by ←** [[TIA Reports and CLI]]
