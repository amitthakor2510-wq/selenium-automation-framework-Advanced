---
title: "ObjectRepository"
type: class
layer: keyword
tags: [fw/keyword, kind/class]
parent: "[[Keyword-Driven Testing]]"
source: "core/keyword/ObjectRepository.java"
loc: 95
---

# ObjectRepository

> [!abstract] Locator store for keyword tests — `key=type:value` lines in `objectrepository/<site>.properties`.

**Part of:** [[Keyword-Driven Testing]]  ·  **Layer:** Keyword-driven  ·  **Source:** `core/keyword/ObjectRepository.java` · 95 LOC

Prefixes: `id name css xpath class linktext partiallinktext tag`. Keeps 'how do I find it' out of both the script and Java. Required for any site whose [[SiteRegistry]] entry has `requiresObjectRepository=true` (demoqa, saucedemo, SAHMAT). SAHMAT's file mixes absolute XPaths with attribute-substring CSS (`input[name*='otp' i]`) for the OTP and CAPTCHA fields.

## Connections

- **Depends on →** [[Site Config Files]]
- **Used by ←** [[KeywordEngine]] · [[KeywordTestBase]]
- **Related ↔** [[SiteRegistry]]
