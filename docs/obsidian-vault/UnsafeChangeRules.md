---
title: "UnsafeChangeRules"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/UnsafeChangeRules.java"
loc: 137
---

# UnsafeChangeRules

> [!abstract] Glob patterns that force a FULL run because the graph can't see their blast radius.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/UnsafeChangeRules.java` · 137 LOC

From `src/test/resources/tia/unsafe-patterns.txt` (or built-in defaults): `pom.xml`, `checkstyle.xml`, `owasp-suppressions.xml`, `Dockerfile`, `docker-compose.yml`, `Jenkinsfile`, `.gitlab-ci.yml`, `testng-suites/**`, `Scripts/**`, `.github/workflows/**`, `config/global.properties`, `config/_TEMPLATE.properties.example`, `logging.properties`, `log4j2.xml`, `allure.properties`. Also dynamic FULL: deleted main file, class missing from compiled output, resource with no literal reference and no inferable site.

## Connections

- **Used by ←** [[TestImpactAnalyzer]]
