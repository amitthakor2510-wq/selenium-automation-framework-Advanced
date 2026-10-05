---
title: "TIA Reports and CLI"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/ReportWriter.java"
loc: 179
---

# TIA Reports and CLI

> [!abstract] `TiaCli` entry point + `ReportWriter` — every output format the toolchain needs.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/ReportWriter.java` · 179 LOC

`target/tia/`: `mode.txt` (`IMPACTED`/`FULL`), `impact-report.md` (why each test), `impacted-tests.txt` (sorted FQCNs), `impacted-tests-<site>.txt`, `testng-impacted-<site>.xml` (ready for `-DsuiteXmlFile`), `impact-summary.json` (read by `compute_dashboard_history.py` for the trend chart). `TiaCli` runs via `exec:java@tia` (profile `tia`) or `java -cp target/classes com.automation.core.tia.TiaCli`.

## Connections

- **Depends on →** [[TestImpactAnalyzer]]
- **Used by ←** [[test-impact-analysis script]] · [[CI Python Scripts]]
- **Related ↔** [[Automation Console]]
