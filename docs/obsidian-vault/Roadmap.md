---
title: "Roadmap"
type: hub
layer: quality
tags: [fw/quality, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Roadmap

> [!abstract] What is finished and what is still open, per the project roadmap.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Quality gates

## Completed (highlights, ~50 items)
API/UI integration · visual regression · Checkstyle gate · JaCoCo gate · multi-browser CI matrix · self-healing (DOM → visual → AI) · Safari · gitleaks · OWASP scan · Dependabot · accessibility · keyword-driven everywhere · Appium · JMeter smoke + Java-DSL load tests · parallel execution · **Test Impact Analysis** (+ coverage fallback, Jenkins/GitLab wiring) · PIT mutation · synthetic data · component-based Page Objects · JSON-schema contract tests · full API framework · AI integration · Grid autoscaling · Slack/Teams notifications · `test-config.properties` · **Automation Console** (3 rounds) · WireMock + API-driven setup · `new-api-site.sh` · site registry >10 sites fix.

## Still open
- Confirm coverage headroom on a real merged run (`Scripts/coverage-headroom.py`).
- Cloud/remote grid integration (BrowserStack, Sauce Labs…) — deliberately deferred; only the self-hosted Docker Grid exists.

See [[Quality Gates]] and [[CI-CD Pipelines]].

## Connections

- **Related ↔** [[Quality Gates]] · [[CI-CD Pipelines]] · [[Test Impact Analysis]] · [[Automation Console]]
