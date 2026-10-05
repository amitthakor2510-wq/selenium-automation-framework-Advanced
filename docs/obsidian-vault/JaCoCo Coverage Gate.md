---
title: "JaCoCo Coverage Gate"
type: tool
layer: quality
tags: [fw/quality, kind/tool]
parent: "[[Quality Gates]]"
---

# JaCoCo Coverage Gate

> [!abstract] Merged coverage gate: every job's `.exec` is unioned, then `jacoco:check` enforces ≥ 50% line coverage on `com.automation.core.*`.

**Part of:** [[Quality Gates]]  ·  **Layer:** Quality gates

Threshold property `jacoco.core.minLineCoverage=0.50` (override `-D`). Inputs: `unit.exec`, each UI site, `mobile`, `<site>-api`, nightly `<site>-perf`. Hard-coded `<argLine>` (AspectJ + logging + heap) means JaCoCo writes its flags to a separate property. Report: `target/site/jacoco/index.html`. `coverage-headroom.py` shows margin. Open roadmap item: confirm headroom on a real merged run.

## Connections

- **Depends on →** [[Coverage Map Pipeline]]
- **Related ↔** [[Maintenance Scripts]] · [[CI-CD Pipelines]]
