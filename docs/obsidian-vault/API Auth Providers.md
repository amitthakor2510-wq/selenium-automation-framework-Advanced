---
title: "API Auth Providers"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/api/auth/AuthProvider.java"
loc: 39
---

# API Auth Providers

> [!abstract] Pluggable `AuthProvider` strategies applied to a RestAssured spec: Bearer, Basic (RFC 7617), API-key (header or query).

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/api/auth/AuthProvider.java` · 39 LOC

`BearerTokenAuthProvider`, `BasicAuthProvider`, `ApiKeyAuthProvider` implement one-method `AuthProvider`; compose via `ApiClient.authenticatedRequest(provider)`. A new scheme (OAuth2, HMAC…) = implement one interface method — no `ApiClient` change.

## Connections

- **Used by ←** [[ApiClient]]
