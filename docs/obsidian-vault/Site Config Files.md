---
title: "Site Config Files"
type: config
layer: config
tags: [fw/config, kind/config]
parent: "[[Configuration System]]"
---

# Site Config Files

> [!abstract] Per-site overrides (`config/<site>.properties`) and keyword locator files (`objectrepository/<site>.properties`).

**Part of:** [[Configuration System]]  ·  **Layer:** Configuration

| Site | URL | Notable overrides |
|---|---|---|
| demoqa | `https://demoqa.com` | `timeout=20`, `mock.record.urlPattern=/BookStore/v1/Books?.*` |
| saucedemo | `https://www.saucedemo.com` | — |
| SAHMAT | `https://staging2.pmgatishakti.gov.in/PermissionPortal/` | `captcha.ai.enabled=true`, `captcha.automation.enabled=true`, `captcha.expected.length=5`, `captcha.ai.crossCheckWithOcr=false`, `captcha.text.charset=A-Za-z0-9` |
| jsonplaceholder | `https://jsonplaceholder.typicode.com` | API-only |
| mobile | (example only) | `mobile.platform=android`, `appium.server.url=http://127.0.0.1:4723`, `mobile.device.name`, `mobile.app.package/activity`, `mobile.noReset`, `mobile.timeout=15`, `mobile.newCommandTimeout=120` |

`_TEMPLATE.properties.example` is the copy source for new sites. **Object repository** lines are `key=type:value` (`id|name|css|xpath|class|linktext|partiallinktext|tag`) — see [[ObjectRepository]].

## Connections

- **Depends on →** [[ConfigReader]]
- **Used by ←** [[ConfigReader]] · [[SiteRegistry]]
- **Related ↔** [[ObjectRepository]] · [[Sites Under Test]] · [[global properties]]
