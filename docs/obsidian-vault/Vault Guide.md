---
title: "Vault Guide"
type: hub
layer: root
tags: [fw/root, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Vault Guide

> [!abstract] How to import and navigate this Obsidian vault — graph colors, tags, properties and the canvas map.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Root

## Install

1. Create (or open) an Obsidian vault and drop **all** `.md` files into one folder (flat is fine — structure comes from links, not folders).
2. Copy `graph.json` into `<vault>/.obsidian/graph.json` (close Obsidian first, or reload). This applies the color groups below.
3. Optional: put `Framework Map.canvas` in the vault root and open it for a structured, grouped overview.
4. Open **Graph view** → set *Display → Arrows* on and leave *Orphans* on.

## Colour legend (graph groups use the `fw/<layer>` tags)

| Colour | Layer tag | Meaning |
|---|---|---|
| ⬜ white | `fw/root` | The single root hub |
| 🟦 blue | `fw/core` | Core framework classes |
| 🟩 teal | `fw/runtime` | Base tests, listeners, evidence utilities |
| 🔷 sky | `fw/config` | Property files and config machinery |
| 🩵 cyan | `fw/data` | Data-driven stack |
| 🟢 lime | `fw/keyword` | Keyword-driven engine |
| 🟧 orange | `fw/healing` | Self-healing locators |
| 🩷 pink | `fw/ai` | AI + CAPTCHA |
| 🟩 emerald | `fw/api` | API, WireMock, performance |
| 🟪 violet | `fw/tia` | Test impact analysis |
| 🟨 amber | `fw/report` | Reports |
| 🟢 green | `fw/site` | Sites under test |
| 🟣 indigo | `fw/mobile` | Appium module |
| 🟥 red | `fw/ci` | CI/CD |
| ⚪ slate | `fw/infra` | Docker, Grid, Dashboard |
| 🟤 stone | `fw/tooling` | Scripts |
| 🟣 lilac | `fw/quality` | Quality gates |

## Every note has

- YAML properties: `type` (hub / class / concept / config / pipeline / tool / site), `layer`, `tags`, `parent`, `source`, `loc`.
- A **Connections** footer: *Depends on →*, *Used by ←*, *Related ↔* — these are real wikilinks, so the graph is the architecture.
- Parent chain: every note points to its layer hub, and every layer hub points to [[Selenium Framework Hub]] — that is the "tree" skeleton; cross-links are the "network".

## Handy graph filters

- `tag:#fw/healing OR tag:#fw/ai` — the resilience layer
- `tag:#kind/class` — only classes
- `path:` is not needed (flat vault) — use tags instead.

## Connections

- **Related ↔** [[Selenium Framework Hub]]
