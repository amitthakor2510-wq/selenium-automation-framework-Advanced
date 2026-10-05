---
title: "BaseApiTest"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/core/BaseApiTest.java"
loc: 44
---

# BaseApiTest

> [!abstract] Base for pure-HTTP API test classes — no browser; configures `ApiClient` in `@BeforeClass` and offers `cleanupAfterClass`.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/core/BaseApiTest.java` · 44 LOC

Counterpart of [[BaseTest]] for non-UI tests; works for UI+API sites and API-only sites alike.

## Connections

- **Depends on →** [[ApiClient]] · [[CleanupRegistry]]
- **Related ↔** [[BaseTest]] · [[API Testing Layer]]
