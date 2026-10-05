---
title: "PerfConfig and Assertions"
type: class
layer: api
tags: [fw/api, kind/class]
parent: "[[Performance Testing]]"
source: "core/perf/PerfConfig.java"
loc: 53
---

# PerfConfig and Assertions

> [!abstract] `PerfConfig` resolves load parameters; `PerfAssertions` turns p99 / error-rate budgets into real TestNG failures.

**Part of:** [[Performance Testing]]  ·  **Layer:** API / mock / perf  ·  **Source:** `core/perf/PerfConfig.java` · 53 LOC

Keys: `perf.threads=10`, `perf.rampUpSeconds=5`, `perf.iterations=5`, `perf.maxP99Millis=5000`, `perf.maxErrorRatePercent=1.0`, `perf.reportBaseDir=target/perf-reports`. Assertions: `assertSamplesRecorded`, `assertErrorRateUnder`, `assertP99Under`. The `.jmx` keeps its own hard-coded thread/ramp values — keep both in sync by hand.

## Connections

- **Used by ←** [[PerfTestBase]]
- **Related ↔** [[ApiAssertions]]
