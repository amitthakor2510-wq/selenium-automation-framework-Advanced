---
title: "DependencyGraph"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/DependencyGraph.java"
loc: 94
---

# DependencyGraph

> [!abstract] Forward graph: class X → project classes whose FQCN appears anywhere in X's constant pool. `reverseTransitiveClosure` finds dependents.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/DependencyGraph.java` · 94 LOC

Deliberately **over-inclusive** (not `CONSTANT_Class` only) so it also catches generics `Signature` attributes, annotation class-literals (`@Test(dataProviderClass = X.class)`) and reflection string literals. Known blind spot: `Class.forName(computedName)` — closed by the [[Coverage Map Pipeline]].

## Connections

- **Depends on →** [[ClassFileScanner]]
- **Used by ←** [[TestImpactAnalyzer]]
