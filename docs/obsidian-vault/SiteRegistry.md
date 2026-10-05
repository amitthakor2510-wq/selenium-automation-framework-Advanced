---
title: "SiteRegistry"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Configuration System]]"
source: "core/config/SiteRegistry.java"
loc: 202
---

# SiteRegistry

> [!abstract] Single source of truth for 'what sites exist and what each needs' — plus the on/off gate from `pipeline-config.properties`.

**Part of:** [[Configuration System]]  ·  **Layer:** Core framework  ·  **Source:** `core/config/SiteRegistry.java` · 202 LOC

`KNOWN_SITES` (a `Map.ofEntries`, **not** `Map.of` — that caps at 10 pairs and broke scaffolding at site #11):

| Site | requiresObjectRepository |
|---|---|
| demoqa | true |
| saucedemo | true |
| SAHMAT | true |
| mobile | false |
| jsonplaceholder | false |

`validate(site)` checks: registered → `config/<site>.properties` exists → object repository exists if required → `isEnabled(site)` per [[pipeline-config properties|pipeline-config.properties]] (**fails closed**: missing line = disabled).
`SiteRegistryConsistencyTest` is the drift guard for the multi-place registration that [[new-site script|new-site.sh]] / [[new-api-site script|new-api-site.sh]] perform.

## Connections

- **Depends on →** [[pipeline-config properties]] · [[Site Config Files]]
- **Used by ←** [[ConfigReader]] · [[SiteMapper]] · [[new-site script]] · [[new-api-site script]]
- **Related ↔** [[enabled-sites script]]
