---
title: "CI Python Scripts"
type: tool
layer: ci
tags: [fw/ci, kind/tool]
parent: "[[CI-CD Pipelines]]"
---

# CI Python Scripts

> [!abstract] Stdlib-only helpers in `.github/workflows/scripts/` that compute and render CI insight.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

`_junit_utils.py` (shared parsing) · `compute_coverage_summary.py` · `compute_dashboard_history.py` · `compute_flaky_trend.py` (→ `target/flaky-tests.json`) · `compute_self_healing_summary.py` · `generate_landing_page.py` (unified test-health page: Allure, Extent, self-healing, flaky trend, coverage, TIA trend) · `generate_segmented_reports.py` · `notify_chat.py` · `post_pr_comment.py` · `print_segment_links.py` · `test_notify_chat.py`.

## Connections

- **Depends on →** [[Segmented Reports]] · [[Chat Notifications]]
- **Used by ←** [[Automation Console]]
- **Related ↔** [[Healing Report Output]]
