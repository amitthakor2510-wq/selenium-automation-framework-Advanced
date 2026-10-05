---
title: "ApiAssertions"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/ApiAssertions.java"
loc: 121
---

# ApiAssertions

> [!abstract] One-line status / schema / response-time / header / array assertions with readable failure messages (body attached on status mismatch).

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/ApiAssertions.java` · 121 LOC

Examples: `assertStatus(response, 200)`, `assertMatchesSchema(response, "schemas/…")`, `assertResponseTimeUnder(response)` (uses `api.responseTime.maxMs`). Prevents each API test hand-rolling slightly different checks.

## Connections

- **Depends on →** [[ApiClient]]
- **Related ↔** [[PerfConfig and Assertions]]
