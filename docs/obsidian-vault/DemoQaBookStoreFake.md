---
title: "DemoQaBookStoreFake"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/mock/DemoQaBookStoreFake.java"
loc: 410
---

# DemoQaBookStoreFake

> [!abstract] Stateful in-memory fake of DemoQA's Account + BookStore API — create → token → add book → read → delete with fresh IDs each run.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/mock/DemoQaBookStoreFake.java` · 410 LOC

Needed because a recording replays one fixed answer. Matches the codes/messages `BookStoreApiTest` and `BookStoreApiNegativeTest` assert. Catalogue is **hand-written seed data (8 books)**, not a capture. `DemoQaBookStoreFakeTest` (6 JUnit 5 tests) runs in `mvn verify -Punit-tests`.

## Connections

- **Used by ←** [[WireMockManager]]
- **Related ↔** [[Unit Tests Profile]]
