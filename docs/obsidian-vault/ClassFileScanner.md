---
title: "ClassFileScanner"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/ClassFileScanner.java"
loc: 103
---

# ClassFileScanner

> [!abstract] Dependency-free reader of the `.class` constant pool — extracts every `CONSTANT_Utf8` per top-level class.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/ClassFileScanner.java` · 103 LOC

Inner/anonymous class strings roll up into the owning top-level class (the granularity of a git-diff line). No ASM or bytecode library — works anywhere a JDK does.

## Connections

- **Used by ←** [[DependencyGraph]]
