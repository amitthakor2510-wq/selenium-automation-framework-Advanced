---
title: "Scripts and Tooling"
type: hub
layer: tooling
tags: [fw/tooling, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Scripts and Tooling

> [!abstract] Shell and Python helpers under `Scripts/` that scaffold sites, drive CI, analyse impact and keep artifacts tidy.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Scripts / tooling

| Group | Scripts |
|---|---|
| Scaffolding | [[new-site script|new-site.sh]] · [[new-api-site script|new-api-site.sh]] |
| CI plumbing | [[enabled-sites script|enabled-sites.sh]] · [[test-impact-analysis script|test-impact-analysis.sh]] · `build-coverage-map.sh` |
| Maintenance | [[Maintenance Scripts]] (`audit-project.sh`, `prune-artifacts.sh`, `install-hooks.sh`, `coverage-headroom.py`, `run-SAHMAT-all-browsers.sh`) |
| Infra | [[grid-autoscaler script|grid-autoscaler.py]] |
| UI | [[Automation Console]] |

Also `ALL_COMMANDS.md` (≈20 KB command cookbook) and `RUNNING.md` (run guide).

## Connections

- **Depends on →** [[new-site script]] · [[new-api-site script]] · [[enabled-sites script]] · [[test-impact-analysis script]] · [[Maintenance Scripts]] · [[grid-autoscaler script]] · [[Automation Console]]
- **Related ↔** [[CI-CD Pipelines]] · [[Maven Profiles]]
