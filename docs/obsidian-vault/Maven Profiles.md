---
title: "Maven Profiles"
type: config
layer: quality
tags: [fw/quality, kind/config]
parent: "[[Quality Gates]]"
---

# Maven Profiles

> [!abstract] Ten opt-in `pom.xml` profiles keep `mvn test` lean.

**Part of:** [[Quality Gates]]  ·  **Layer:** Quality gates

`perf` (JMeter smoke) · `security` (OWASP) · `mutation` (PIT) · `unit-tests` (Failsafe+JUnit5) · `tia` (exec TiaCli) · `bug-crawler` · `mobile-bug-crawler` · `prune-artifacts` · `coverage-map` · `reportportal`. Surefire is pinned to the TestNG provider with a literal argLine; plugins also include allure-maven, jacoco, checkstyle, failsafe, exec-maven-plugin ×5.

## Connections

- **Related ↔** [[Tech Stack]] · [[Scripts and Tooling]]
