---
title: "test-impact-analysis script"
type: tool
layer: tooling
tags: [fw/tooling, kind/tool]
parent: "[[Scripts and Tooling]]"
aliases: [test-impact-analysis.sh]
---

# test-impact-analysis script

> [!abstract] Wrapper that runs TIA and optionally executes each impacted per-site suite.

**Part of:** [[Scripts and Tooling]]  ·  **Layer:** Scripts / tooling

`--base <ref> [--head <ref>] [--run] [--browser <b>]`. Reads `target/tia/mode.txt`; `IMPACTED` loops `mvn test -Dsite=<s> -DsuiteXmlFile=target/tia/testng-impacted-<s>.xml`, `FULL` runs everything. Companion `build-coverage-map.sh <site> [suite]` refreshes `coverage-map.txt`.

## Connections

- **Depends on →** [[TIA Reports and CLI]]
- **Related ↔** [[Test Impact Analysis]] · [[Coverage Map Pipeline]]
