---
title: "Project Structure"
type: concept
layer: core
tags: [fw/core, kind/concept]
parent: "[[Selenium Framework Hub]]"
---

# Project Structure

> [!abstract] Repository layout — what lives where and which package boundaries are enforced.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Core framework

```text
selenium-automation-framework/
├── Jenkinsfile · .gitlab-ci.yml · .github/workflows/      → CI-CD Pipelines
├── pipeline-config.properties · test-config.properties     → site / test switches
├── docker-compose{,.override,.autoscale,.dashboard}.yml · Dockerfile · Dockerfile.dashboard
├── pom.xml · checkstyle.xml · gitleaks.toml · owasp-suppressions.xml
├── Scripts/            (new-site.sh, new-api-site.sh, grid-autoscaler.py, dashboard/, …)
├── testng-suites/      (23 suite XMLs)
├── docs/               (13 deep-dive guides) · README · CONVENTIONS · ALL_COMMANDS · RUNNING · DOCKER · KEYWORD_DRIVEN_TESTING
├── perf/basic-smoke.jmx · self-healing-data/locator-repository.json
└── src/
    ├── main/java/com/automation/
    │   ├── core/        base · config · driver · data · keyword · selfhealing · ai · crawler · tia · coverage · report · retention · utils · components · exceptions
    │   ├── sites/       demoqa/pages (32) · saucedemo/pages (1)
    │   ├── mobile/      core · crawler · sites/settings/pages
    │   └── template/    TemplatePage (reference only)
    └── test/
        ├── java/com/automation/
        │   ├── core/    api · mock · perf · tia tests · data tests · config tests
        │   ├── sites/   core (BaseTest…) · listeners · demoqa · saucedemo · sahmat · jsonplaceholder
        │   └── mobile/  core · sites/settings
        └── resources/   config · objectrepository · testdata · schemas · tia · wiremock · log4j2 …
```

**Rules of the layout**
- `src/main/core` is **never site-specific**; `sites/<name>` holds Page Objects only.
- `rest-assured`, `wiremock`, `jmeter-dsl` are *test-scoped* → `core/api`, `core/mock`, `core/perf` therefore live under `src/test`.
- A `TemplatePage`/`TemplateTest` pair compiles and is Checkstyle-checked but sits in a package no suite matches → see [[Template Scaffold]].
- `.idea/`, `.allure/`, `.git/` and `target/` are tooling noise.

## Connections

- **Related ↔** [[Tech Stack]] · [[Core Framework]] · [[Sites Under Test]] · [[Template Scaffold]]
