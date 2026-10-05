---
title: "Test Types and Suites"
type: hub
layer: runtime
tags: [fw/runtime, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Test Types and Suites

> [!abstract] The 23 TestNG suite XMLs and the group taxonomy that decides what each run executes.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Test runtime

## Groups
`smoke` (fast, gates every PR) · `regression` (full, nightly) · `api` · `perf` · `accessibility` · `visual` · `keyword-driven` · `data-driven` · `synthetic-data`. **A `@Test` without `groups` never runs in any suite.**

## Suites (`testng-suites/`)

| Suite family | Files | Notes |
|---|---|---|
| demoqa UI | `demoqa-smoke`, `demoqa-regression` | `parallel="classes"`, thread-count 3 / 2 (matches Grid `SE_NODE_MAX_SESSIONS`) |
| demoqa specialised | `demoqa-accessibility`, `demoqa-visual`, `demoqa-synthetic-data`, `demoqa-perf` | opt-in / nightly |
| saucedemo | `saucedemo-smoke`, `saucedemo-regression` | login in 3 styles |
| SAHMAT | `SAHMAT-smoke`, `SAHMAT-regression` | keyword-driven, CAPTCHA |
| Safari | `*-safari-smoke`, `*-safari-regression` | `parallel="none"` — one Safari session per machine |
| API | `api-tests`, `api-tests-mocked`, `api-tests-jsonplaceholder` | no browser |
| Perf | `demoqa-perf`, `jsonplaceholder-perf` | JMeter Java DSL |
| Mobile | `mobile-smoke`, `mobile-regression` | package `mobile.sites.*` |

A suite *package* match means a new test class is picked up automatically — only `groups` matters. Run one with:

```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
```
> `-Dsite` and `-DsuiteXmlFile` must be a matched pair — the [[Automation Console]] "Copy run command" button exists for exactly this.

## Connections

- **Related ↔** [[Test Execution Runtime]] · [[Sites Under Test]] · [[test-config properties]] · [[Scripts and Tooling]]
