---
title: "GitHub Actions Pipeline"
type: pipeline
layer: ci
tags: [fw/ci, kind/pipeline]
parent: "[[CI-CD Pipelines]]"
---

# GitHub Actions Pipeline

> [!abstract] `.github/workflows/github-ci.yml` — 22 jobs, push/PR to main, manual dispatch, nightly cron `0 2 * * *`; the only pipeline with live Allure trend history on Pages.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

**Jobs:** `build` · `checkstyle` · `unit-tests` · `test-impact-analysis` · `coverage-map` · `matrix-setup` · `test` (site × chrome/firefox/edge, +safari on macOS for pushes → 8 instances, 6 on PR/schedule) · `mobile-test` · `coverage-gate` · `accessibility-visual-test` (nightly) · `api-tests` · `perf-tests` · `security-scan` (nightly) · `secret-scan` · `secret-scan-full-history` · `pr-comment` · `allure-report` · `notify`.

**Dispatch inputs:** `suite_type`, `browser`, `headless`, `retry_count`, `run_safari`, `record_video`.

`allure-report` merges results + previous `history` from `gh-pages` (`keep_reports: 20`) and deploys Allure + Extent. Extra workflow `gh-pages-retention.yml` squashes history weekly. Dependabot (`dependabot.yml`) keeps Maven + Actions current.

## Connections

- **Depends on →** [[CI Python Scripts]] · [[gh-pages Retention]] · [[Chat Notifications]]
- **Related ↔** [[Segmented Reports]] · [[Allure Report]]
