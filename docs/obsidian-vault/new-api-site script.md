---
title: "new-api-site script"
type: tool
layer: tooling
tags: [fw/tooling, kind/tool]
parent: "[[Scripts and Tooling]]"
aliases: [new-api-site.sh]
---

# new-api-site script

> [!abstract] `./Scripts/new-api-site.sh <name> <base-url>` — API-only site scaffold (no browser, no page objects).

**Part of:** [[Scripts and Tooling]]  ·  **Layer:** Scripts / tooling

Automates the manual checklist from the docs (`jsonplaceholder` is the reference): registers the site, writes config, `pipeline-config` lines incl. `type=api`, API and perf suites and tests.

## Connections

- **Depends on →** [[SiteRegistry]] · [[SiteMapper]]
- **Related ↔** [[new-site script]] · [[JsonPlaceholder]]
