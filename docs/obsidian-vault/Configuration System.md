---
title: "Configuration System"
type: hub
layer: config
tags: [fw/config, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Configuration System

> [!abstract] Three config surfaces: runtime properties (layered), pipeline site switches, and test-level switches.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Configuration

| Surface | File | Fails | Controls |
|---|---|---|---|
| Runtime settings | [[global properties|global.properties]] → [[Site Config Files]] → `-D` | — | browser, timeouts, retry, pauses, healing, AI, CAPTCHA, API, perf, video |
| Which **sites** run | [[pipeline-config properties|pipeline-config.properties]] | **closed** | GitHub Actions matrix, Jenkins, GitLab, local `mvn` |
| Which **tests** run | [[test-config properties|test-config.properties]] | **open** | classes, methods, packages, groups, `run.only` |

[[ConfigReader]] resolves the first; [[SiteRegistry]] enforces the second; [[TestSelection]] applies the third. The [[Automation Console]] edits the second and third from a browser.

## Connections

- **Depends on →** [[ConfigReader]] · [[SiteRegistry]] · [[TestSelection]]
- **Related ↔** [[global properties]] · [[pipeline-config properties]] · [[test-config properties]] · [[Site Config Files]]
