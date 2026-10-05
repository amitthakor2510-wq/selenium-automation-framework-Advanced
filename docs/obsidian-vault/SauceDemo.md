---
title: "SauceDemo"
type: site
layer: site
tags: [fw/site, kind/site]
parent: "[[Sites Under Test]]"
---

# SauceDemo

> [!abstract] Reference site: the same login flow written three ways — hard-coded, data-driven and keyword-driven.

**Part of:** [[Sites Under Test]]  ·  **Layer:** Sites under test

`LoginTest` (classic) · `LoginDataDrivenTest` (rows from `login.csv/.json/.xlsx/.yaml/.zip`) · `KeywordDrivenLoginTest` (`saucedemo_login_keywords.csv`, 3 cases; locators `saucedemo.login.username=id:user-name`). One Page Object: `LoginPage`. Currently **disabled** in `pipeline-config.properties`. Config `url=https://www.saucedemo.com`.

## Connections

- **Depends on →** [[BasePage]] · [[DataProvider]] · [[KeywordTestBase]]
- **Related ↔** [[Data-Driven Testing]] · [[Keyword-Driven Testing]]
