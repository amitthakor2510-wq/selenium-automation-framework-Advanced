---
title: "Performance Testing"
type: hub
layer: api
tags: [fw/api, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Performance Testing

> [!abstract] Two ways to load-test: a JMeter `.jmx` smoke (legacy) and a JMeter-Java-DSL framework where perf tests are ordinary TestNG classes.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** API / mock / perf

| | JMeter smoke | Java DSL (recommended) |
|---|---|---|
| Where | `perf/basic-smoke.jmx` | `core/perf/` + one `*PerfTest` per site |
| Run | `mvn verify -Pperf` (`-Dthreads -DrampUp -Dloops -DmaxResponseMs`) | `mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf` |
| Config | `-D` flags only | `ConfigReader` layers (`perf.*`) |
| Reporting | JMeter HTML (`target/jmeter/reports`) | Allure/Extent/ReportPortal **+** `target/perf-reports/` |

Perf tests are **opt-in** (group `perf`, outside smoke/regression) because pass/fail depends on environment-sensitive budgets. In CI they run nightly (`perf-tests` job; Jenkins *Performance Tests – Java DSL (Nightly)*; Jenkins/GitLab also run the `.jmx` *Performance Smoke*). Add a site: extend `PerfTestBase`, one `@Test(groups="perf")` calling `runGetLoadTest(...)`, copy a `<site>-perf.xml`.

## Connections

- **Depends on →** [[PerfTestBase]] · [[PerfConfig and Assertions]] · [[JMeter Smoke Plan]]
- **Related ↔** [[API Testing Layer]] · [[CI-CD Pipelines]] · [[global properties]]
