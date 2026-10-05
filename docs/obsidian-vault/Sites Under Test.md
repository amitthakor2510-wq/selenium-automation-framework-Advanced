---
title: "Sites Under Test"
type: hub
layer: site
tags: [fw/site, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Sites Under Test

> [!abstract] Five targets: demoqa (deep POM + API), saucedemo (3 styles), SAHMAT (gov portal, keyword + CAPTCHA), jsonplaceholder (API-only), Android Settings (mobile).

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Sites under test

| Site | Type | Entry in `pipeline-config` | Style |
|---|---|---|---|
| [[DemoQA]] | UI + REST | enabled | POM (32 pages, 42 test files), API, a11y, visual, perf, synthetic, keyword |
| [[SauceDemo]] | UI | **disabled** | same login in plain / data-driven / keyword styles |
| [[SAHMAT]] | UI (gov portal) | enabled | keyword-driven + CAPTCHA + self-healing |
| [[JsonPlaceholder]] | API-only | enabled (`type=api`) | Rest-Assured + schema + perf |
| Android Settings | mobile | enabled | see [[Mobile Appium Module]] |

Add a site with one command: [[new-site script|new-site.sh]] (UI) or [[new-api-site script|new-api-site.sh]] (API) — they register it in [[SiteRegistry]] / `SiteMapper`, create config + suites + all three test styles, and wire CI. See [[Template Scaffold]].

## Connections

- **Depends on →** [[DemoQA]] · [[SauceDemo]] · [[SAHMAT]] · [[JsonPlaceholder]] · [[Mobile Appium Module]]
- **Related ↔** [[Site Config Files]] · [[pipeline-config properties]] · [[Test Types and Suites]]
