---
title: "pipeline-config properties"
type: config
layer: config
tags: [fw/config, kind/config]
parent: "[[Configuration System]]"
aliases: [pipeline-config.properties]
---

# pipeline-config properties

> [!abstract] Master SITE on/off switch — one line per site, read by all three CI systems, scripts, the dashboard and Java.

**Part of:** [[Configuration System]]  ·  **Layer:** Configuration

Format `site.<name>.enabled=true|false`; API-only sites add `site.<name>.type=api`.

Current: demoqa **true** · saucedemo **false** · mobile **true** · SAHMAT **true** · jsonplaceholder **true** (`type=api`).

**Fails closed** — a missing line means disabled. Readers: [[enabled-sites script|enabled-sites.sh]] (shared by GitHub/Jenkins/GitLab), [[SiteRegistry]] (`validate()`), [[Automation Console]] (toggles + presets). Disabling a site removes its matrix jobs, coverage-map run, mobile/a11y jobs — no workflow edits.

## Connections

- **Used by ←** [[SiteRegistry]] · [[enabled-sites script]] · [[Automation Console]] · [[CI-CD Pipelines]]
- **Related ↔** [[test-config properties]]
