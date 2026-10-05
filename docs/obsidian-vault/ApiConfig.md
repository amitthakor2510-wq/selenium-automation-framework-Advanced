---
title: "ApiConfig"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/ApiConfig.java"
loc: 99
---

# ApiConfig

> [!abstract] Resolves the base URI and API-wide settings through the standard ConfigReader layers; returns the local WireMock URL when mocking is on.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/ApiConfig.java` · 99 LOC

Keys: `api.retry.count=0`, `api.retry.backoffMs=500`, `api.responseTime.maxMs=5000`, `mock.enabled`. `baseUri()` flips to WireMock automatically, so every class using `ApiClient` is mocked with zero test changes. `DemoQaAccountApi.real()` always targets the live site (browsers navigate there); `.current()` follows `-Dmock.enabled`.

## Connections

- **Depends on →** [[ConfigReader]] · [[WireMockManager]]
- **Used by ←** [[ApiClient]] · [[PerfTestBase]]
