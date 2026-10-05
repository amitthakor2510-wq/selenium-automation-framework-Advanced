---
title: "CleanupRegistry"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/CleanupRegistry.java"
loc: 77
---

# CleanupRegistry

> [!abstract] LIFO list of 'undo' actions registered as a test creates data, run after the method/class; a failing undo is logged, never rethrown.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/CleanupRegistry.java` · 77 LOC

Used through `BaseTest.cleanupAfterMethod/Class` and `BaseApiTest.cleanupAfterClass`. Fixed the old problem where every `BookStoreApplicationTest` run left an `AutoTest_*` user behind (now deleted by credentials after the class).

## Connections

- **Used by ←** [[BaseTest]] · [[BaseApiTest]] · [[DemoQA]]
