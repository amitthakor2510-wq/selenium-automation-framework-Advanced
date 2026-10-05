---
title: "WireMockManager"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[API Testing Layer]]"
source: "core/mock/WireMockManager.java"
loc: 253
---

# WireMockManager

> [!abstract] One shared in-process WireMock server per JVM — offline API tests, forced error states and recorded stubs. Opt-in with `-Dmock.enabled=true`.

**Part of:** [[API Testing Layer]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/mock/WireMockManager.java` · 253 LOC

**Response priority:** recorded stubs in `src/test/resources/wiremock/<site>/` (priority 5) → [[DemoQaBookStoreFake]] (priority 10) → 404 naming the unsupported route.

```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests-mocked.xml -Dmock.enabled=true
# record: add -Dmock.record=true  (knobs: mock.record.target / .method=GET|ANY / .urlPattern)
```
Force errors: `WireMockManager.server().stubFor(get(urlEqualTo("/__mock__/x")).willReturn(serverError()))`. Limits: mocks only RestAssured/`ApiClient` calls (a browser still hits the real site); the server is shared across parallel classes — use URLs no other class touches. `api-tests-mocked.xml` runs 3 classes with 3 threads.

## Connections

- **Depends on →** [[DemoQaBookStoreFake]]
- **Used by ←** [[ApiConfig]]
- **Related ↔** [[ApiRetry]]
