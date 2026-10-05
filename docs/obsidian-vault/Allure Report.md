---
title: "Allure Report"
type: tool
layer: report
tags: [fw/report, kind/tool]
parent: "[[Reporting and Observability]]"
---

# Allure Report

> [!abstract] Interactive report with history/trends — `@Step`s from HumanActions, attachments, severity labels, flaky tags.

**Part of:** [[Reporting and Observability]]  ·  **Layer:** Reporting

Allure 2.27.0 (CLI bundled in `.allure/`), `allure-testng` SPI listener, AspectJ weaver for `@Step`. Severity: `smoke`→critical else normal. GitHub Actions publishes it with trend history to Pages (`/allure-report`); GitLab publishes under `public/`; Jenkins uses the Allure plugin. Local: `allure serve target/allure-results`.

## Connections

- **Depends on →** [[AllureEnvironmentWriter]]
- **Used by ←** [[Segmented Reports]]
- **Related ↔** [[TestListener]] · [[HumanActions]] · [[GitHub Actions Pipeline]]
