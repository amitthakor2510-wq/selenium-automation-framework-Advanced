---
title: "JMeter Smoke Plan"
type: tool
layer: api
tags: [fw/api, kind/tool]
parent: "[[Performance Testing]]"
---

# JMeter Smoke Plan

> [!abstract] `perf/basic-smoke.jmx` — response-time/response-code smoke via the `perf` Maven profile.

**Part of:** [[Performance Testing]]  ·  **Layer:** API / mock / perf

Hand-edited XML run by `jmeter-maven-plugin` (`mvn verify -Pperf`). Results `target/jmeter/results/`. Marks the build UNSTABLE (not failed) on breach in Jenkins since it hits sites the repo does not control. Kept because it is the quickest eyeball check and opens in the JMeter GUI.

## Connections

- **Related ↔** [[Maven Profiles]] · [[Jenkins Pipeline]]
