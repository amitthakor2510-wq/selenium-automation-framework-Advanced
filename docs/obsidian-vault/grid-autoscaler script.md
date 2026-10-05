---
title: "grid-autoscaler script"
type: tool
layer: infra
tags: [fw/infra, kind/tool]
parent: "[[Docker and Selenium Grid]]"
aliases: [grid-autoscaler.py]
---

# grid-autoscaler script

> [!abstract] Polls the hub's GraphQL queue and scales browser pool services up/down.

**Part of:** [[Docker and Selenium Grid]]  ·  **Layer:** Infra / Docker

`Scripts/grid-autoscaler.py` (388 LOC) works with `docker-compose.autoscale.yml` pools. Fixed an earlier path bug in the bug-audit round.

## Connections

- **Related ↔** [[Docker and Selenium Grid]]
