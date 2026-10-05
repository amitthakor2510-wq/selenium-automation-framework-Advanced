---
title: "Jenkins Pipeline"
type: pipeline
layer: ci
tags: [fw/ci, kind/pipeline]
parent: "[[CI-CD Pipelines]]"
---

# Jenkins Pipeline

> [!abstract] `Jenkinsfile` (~135 KB) — parameterised, per-site parallel branches, nightly extras.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

**Setup:** JDK `JDK17`, Maven `Maven3`, Allure tool `allure`, HTML Publisher plugin; mobile stage needs an Android-capable agent (Node, wget/unzip, ideally `/dev/kvm`).

**Parameters:** `SUITE_TYPE` (regression/smoke) · `SITE` (ALL/site/mobile) · `BROWSER` · `ALL_BROWSERS` · `HEADLESS` · `RECORD_VIDEO` (forces headed + `xvfb-run`) · `RETRY_COUNT` (0) · `SECURITY_FAIL_CVSS` (11 = report-only) · `REPORTPORTAL_*`.

**Stages:** Acquire Shared-Box Lock → Cleanup (stale node/adb/qemu + AVD locks) → Restore Self-Healing Cache → Checkout → Build → Checkstyle → Unit Tests (`-Punit-tests`) → Secret Scan → Test Impact Analysis → Discover Site Projects (globs `testng-suites/*-<type>.xml`, drops Safari) → **Run Tests Per Site** (parallel; each branch writes its own `target/jacoco-artifacts/<key>.exec`) → API Tests → Mobile Test → Performance Tests – Java DSL (nightly) → Coverage Gate → Nightly Extra Coverage → Performance Smoke (nightly) → Security Scan (nightly).

`buildDiscarder` keeps 10 builds; `post{always}` runs `adb reconnect offline`, chat notify, `cleanWs()`. Local shared-box note: Jenkins + self-hosted GitLab + emulator on one machine starved GitLab (puma/sidekiq) — mitigated by capping `sidekiq['concurrency']`.

## Connections

- **Depends on →** [[enabled-sites script]] · [[Chat Notifications]] · [[LocatorRepository]]
- **Related ↔** [[Docker and Selenium Grid]] · [[Mobile Appium Module]]
