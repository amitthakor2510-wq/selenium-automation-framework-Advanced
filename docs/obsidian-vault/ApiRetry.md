---
title: "ApiRetry"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/ApiRetry.java"
loc: 109
---

# ApiRetry

> [!abstract] Explicit, opt-in exponential-backoff retry for one flaky call — `ApiRetry.withRetry(() -> ApiClient.get(path))`.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/ApiRetry.java` · 109 LOC

Retries only transient failures (timeouts/connection resets thrown as exceptions, or retryable statuses); a persistent failure gives up after 1 + N attempts. Deliberately **not automatic** — most API failures are contract violations worth surfacing immediately.

## Connections

- **Used by ←** [[ApiClient]]
- **Related ↔** [[WireMockManager]]
