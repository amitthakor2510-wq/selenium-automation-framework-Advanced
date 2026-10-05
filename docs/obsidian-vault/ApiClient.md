---
title: "ApiClient"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/ApiClient.java"
loc: 129
---

# ApiClient

> [!abstract] Thin RestAssured wrapper: base URI, auth headers, request/response logging, one-line verb helpers.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/ApiClient.java` · 129 LOC

`configure()` (adds `AllureRestAssured`; `api.log.onFailureOnly=true` prints only on assertion failure), `jsonRequest`, `authorizedRequest`, `authenticatedRequest(provider)`, `request`, `get/post/put/patch/delete(path)`. Identical for UI+API sites (demoqa) and pure API sites (jsonplaceholder). Lives in `src/test` because `rest-assured` is test-scoped.

## Connections

- **Depends on →** [[ApiConfig]] · [[API Auth Providers]]
- **Used by ←** [[BaseApiTest]] · [[ApiAssertions]]
- **Related ↔** [[ApiRetry]]
