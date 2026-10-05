---
title: "enabled-sites script"
type: tool
layer: tooling
tags: [fw/tooling, kind/tool]
parent: "[[Scripts and Tooling]]"
aliases: [enabled-sites.sh]
---

# enabled-sites script

> [!abstract] Single reader of `pipeline-config.properties` for all CI systems.

**Part of:** [[Scripts and Tooling]]  ·  **Layer:** Scripts / tooling

Flags: *(none)* newline list · `--browser-only` (drops mobile + `type=api`) · `--api-only` · `--json` (e.g. `["demoqa"]`). Keeps every pipeline's site list identical.

## Connections

- **Depends on →** [[pipeline-config properties]]
- **Used by ←** [[Jenkins Pipeline]] · [[GitHub Actions Pipeline]] · [[GitLab CI Pipeline]]
