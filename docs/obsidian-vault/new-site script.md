---
title: "new-site script"
type: tool
layer: tooling
tags: [fw/tooling, kind/tool]
parent: "[[Scripts and Tooling]]"
aliases: [new-site.sh]
---

# new-site script

> [!abstract] `./Scripts/new-site.sh <name> <url>` — scaffolds a new UI site across all three test styles and wires CI.

**Part of:** [[Scripts and Tooling]]  ·  **Layer:** Scripts / tooling

Creates config + suites + a Page Object/test (standard), keyword script + object repository (keyword-driven) and data file + test (data-driven); registers in `SiteRegistry`/`SiteMapper`, `pipeline-config.properties`, and the three CI definitions. Fixed bugs: GitLab `sed` matched every `SITE: [..]` line (polluted API/perf matrices); reintroduced Allure listener conflict; 10-site `Map.of` cap.

## Connections

- **Depends on →** [[SiteRegistry]] · [[SiteMapper]] · [[Template Scaffold]] · [[pipeline-config properties]]
- **Related ↔** [[new-api-site script]] · [[Sites Under Test]]
