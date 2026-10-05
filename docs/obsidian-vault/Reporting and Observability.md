---
title: "Reporting and Observability"
type: hub
layer: report
tags: [fw/report, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Reporting and Observability

> [!abstract] Every result flows down two paths at once — Extent (single HTML) and Allure (interactive, with history) — plus optional ReportPortal, JaCoCo, video and segmented CI reports.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Reporting

```text
target/
├── extent-reports/<site>/<browser-or-mobile>/<suite>/index.html
├── allure-results/            environment.properties · categories.json · *-result.json
├── allure-segmented/          browser/ site/ testType/ category/ + segments.json
├── videos/ · screenshots/ · debug-dumps/ · self-healing/ · perf-reports/ · crawler/ · tia/
├── surefire-reports/*.xml · site/jacoco/index.html
└── logs/<site>.log · chromedriver-<browser>.log
```

**Attachments by outcome** — pass: screenshot · fail/skip: screenshot + page source + browser console + failed URL (+ AI root cause, video).

[[TestListener]] is the single driver; [[ExtentManager]] and [[AllureEnvironmentWriter]] feed the two engines.

## Connections

- **Depends on →** [[TestListener]] · [[ExtentManager]] · [[AllureEnvironmentWriter]] · [[Allure Report]] · [[Extent Report]] · [[ReportPortal]] · [[Segmented Reports]] · [[JaCoCo Coverage Gate]]
- **Related ↔** [[ScreenshotUtil]] · [[FailureDiagnostics]] · [[VideoRecorder]] · [[Healing Report Output]] · [[CI-CD Pipelines]]
