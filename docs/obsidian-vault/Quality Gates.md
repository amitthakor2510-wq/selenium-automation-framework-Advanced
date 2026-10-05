---
title: "Quality Gates"
type: hub
layer: quality
tags: [fw/quality, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Quality Gates

> [!abstract] Every automated gate and how strict it is: Checkstyle, JaCoCo, unit tests, mutation, OWASP, gitleaks.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Quality gates

| Gate | Command | Strictness |
|---|---|---|
| [[Checkstyle Gate]] | `checkstyle:check@checkstyle-check` / `mvn verify` | GitHub/GitLab job fail · Jenkins UNSTABLE |
| [[JaCoCo Coverage Gate]] | `jacoco:merge` + `jacoco:check@jacoco-check` | 50% lines on `com.automation.core.*` |
| [[Unit Tests Profile]] | `mvn verify -Punit-tests` | fails pipeline (GitHub/GitLab) · UNSTABLE (Jenkins) |
| Mutation (PIT) | `mvn verify -Pmutation` | opt-in |
| OWASP dependency-check | `mvn verify -Psecurity` | nightly; report-only (`failBuildOnCVSS=11`) |
| [[Secret Scanning]] | gitleaks | report-only for now |
| Perf budgets | p99 / error-rate asserts | nightly, UNSTABLE |

## Connections

- **Depends on →** [[Checkstyle Gate]] · [[JaCoCo Coverage Gate]] · [[Unit Tests Profile]] · [[Secret Scanning]] · [[Maven Profiles]]
- **Related ↔** [[CI-CD Pipelines]] · [[Roadmap]]
