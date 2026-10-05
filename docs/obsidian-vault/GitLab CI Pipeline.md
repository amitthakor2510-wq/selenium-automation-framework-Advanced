---
title: "GitLab CI Pipeline"
type: pipeline
layer: ci
tags: [fw/ci, kind/pipeline]
parent: "[[CI-CD Pipelines]]"
---

# GitLab CI Pipeline

> [!abstract] `.gitlab-ci.yml` (~67 KB) on a **self-hosted GitLab** — stages config → build → test → report → pages.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

**Jobs:** `generate-pipeline-config` (exports enabled sites as dotenv) · `build` · `checkstyle` · `unit-tests` · `test` (`parallel:matrix` site × browser = 6) · `mobile-test` (serialized by `resource_group` on a persistent shared shell runner) · `accessibility-visual-test` / `security-scan` (schedules) · `secret-scan` · `test-impact-analysis` (MR) · `api-tests` · `perf-tests` · `perf-smoke` · `coverage-gate` · `report` (merges Allure, prunes videos to 10 runs) · `pages` (`expire_in: 30 days`) · `notify`.

Setup history: self-signed cert (`gitlab-selfsigned.crt`) trust, runner registration, `[runners.cache]` fixes, GitHub↔GitLab push conflicts. Variables: `SUITE_TYPE`, `BROWSER`, `RETRY_COUNT`, `SECURITY_FAIL_CVSS`, `REPORTPORT_*`. Open item per notes: some newly added job wiring still incomplete.

## Connections

- **Depends on →** [[ArtifactRetentionCleaner]] · [[Chat Notifications]] · [[Segmented Reports]]
- **Related ↔** [[Docker and Selenium Grid]]
