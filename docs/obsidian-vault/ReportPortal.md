---
title: "ReportPortal"
type: tool
layer: report
tags: [fw/report, kind/tool]
parent: "[[Reporting and Observability]]"
---

# ReportPortal

> [!abstract] Optional live results stream (`agent-java-testng`) with server-side history and flaky analytics. Complete no-op unless `-Dreportportal.enable=true`.

**Part of:** [[Reporting and Observability]]  ·  **Layer:** Reporting

Auto-registered via `META-INF/services/org.testng.ITestNGListener`; config in `src/test/resources/reportportal.properties`. CI parameters `REPORTPORTAL_ENABLE/ENDPOINT/PROJECT` (Jenkins/GitLab) and secrets (GitHub); Maven profile `reportportal`.

## Connections

- **Related ↔** [[Maven Profiles]] · [[CI-CD Pipelines]]
