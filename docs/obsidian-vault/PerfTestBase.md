---
title: "PerfTestBase"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[Performance Testing]]"
source: "core/perf/PerfTestBase.java"
loc: 119
---

# PerfTestBase

> [!abstract] Base class for JMeter-Java-DSL load tests — same `-Dsite` config, reporting and suite mechanics as every other test type.

**Part of:** [[Performance Testing]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/perf/PerfTestBase.java` · 119 LOC

`runGetLoadTest(reportName, relativePath)` resolves the path against the site base URI (via [[ApiConfig]]). Proof of the extension pattern: a new test kind = a `*Test` extending the right `Base*Test`. Concrete tests: `DemoQaHomePagePerfTest`, `JsonPlaceholderApiPerfTest`.

## Connections

- **Depends on →** [[ApiConfig]] · [[PerfConfig and Assertions]]
- **Related ↔** [[BaseApiTest]]
