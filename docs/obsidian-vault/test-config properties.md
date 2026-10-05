---
title: "test-config properties"
type: config
layer: config
tags: [fw/config, kind/config]
parent: "[[Configuration System]]"
aliases: [test-config.properties]
---

# test-config properties

> [!abstract] Test-level on/off file at repo root — one `test.<Class>.enabled=true` line per class, groups, methods and `run.only`.

**Part of:** [[Configuration System]]  ·  **Layer:** Configuration

Applies everywhere with no other edit (local, GitHub, GitLab, Jenkins, Docker) because it lives at the working-directory root. **Fails open**: only an explicit `=false` (or `run.only`/`-Dtests.*`) switches anything off. Sectioned with `# ── … ──` headers that the [[Automation Console]] turns into collapsible groups with *Enable all / Disable all*.

Enforced by [[TestSelection]]. Override per run without editing: `-Dtests.run.only=…`, `-Dtests.disabled=…`, `-Dtest.config.file=…`.

## Connections

- **Used by ←** [[TestSelection]] · [[Automation Console]]
- **Related ↔** [[pipeline-config properties]] · [[Test Types and Suites]]
