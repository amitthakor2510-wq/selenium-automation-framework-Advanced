---
title: "Coverage Map Pipeline"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/coverage/CoverageMapBuilder.java"
loc: 125
---

# Coverage Map Pipeline

> [!abstract] Second, runtime signal: which test class *actually executed* which classes — closes the reflection blind spot. CoverageMap + CoverageMapBuilder + CoverageExecReader + two TestNG listeners.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/coverage/CoverageMapBuilder.java` · 125 LOC

1. `Scripts/build-coverage-map.sh <site>` runs the site suite with `-Dcoverage.map.enabled=true -Djacoco.jmx=true`.
2. `AlterSuiteForCoverageMapListener` forces `parallel="none"`, `thread-count=1` (per-class capture only makes sense serially); no-op otherwise.
3. `JacocoPerTestCoverageListener` resets the JaCoCo runtime before each class and dumps one `.exec` per class to `target/jacoco-per-test/` (no-op without the JMX MBean).
4. `CoverageMapBuilder` + `CoverageExecReader` (the **only** class importing `org.jacoco.core`) reduce them to `target/tia/coverage-map.txt` (`testFqcn<TAB>coveredClassFqcn`). "Executed" = at least one probe fired.
5. `CoverageMap` answers "which tests touched class X" — additive and optional; TIA still runs without `org.jacoco.core`.

CI: GitHub `coverage-map` job refreshes it per site (not on every PR). JaCoCo can't see file I/O, so config/resource blind spots stay with the site/resource fallbacks.

## Connections

- **Depends on →** [[Test Execution Runtime]]
- **Used by ←** [[TestImpactAnalyzer]]
- **Related ↔** [[JaCoCo Coverage Gate]] · [[Maintenance Scripts]]
