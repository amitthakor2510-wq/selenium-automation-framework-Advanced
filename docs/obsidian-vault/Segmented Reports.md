---
title: "Segmented Reports"
type: tool
layer: report
tags: [fw/report, kind/tool]
parent: "[[Reporting and Observability]]"
---

# Segmented Reports

> [!abstract] `generate_segmented_reports.py` — Allure only builds one report per results folder, so results are re-sliced and `allure generate` runs per dimension.

**Part of:** [[Reporting and Observability]]  ·  **Layer:** Reporting

Produces separate `report/index.html` per browser, site/app, test type (suite), severity and TestNG group category under `target/allure-segmented/`, plus `segments.json` read by the landing page and job summaries — so nobody opens the Firefox run thinking it was Chrome.

## Connections

- **Depends on →** [[Allure Report]]
- **Used by ←** [[CI Python Scripts]]
