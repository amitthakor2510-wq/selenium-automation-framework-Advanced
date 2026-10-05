---
title: "KeywordTestBase"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/core/KeywordTestBase.java"
loc: 35
---

# KeywordTestBase

> [!abstract] Extend instead of BaseTest for keyword-driven classes — adds `runKeywordTestCase(objectRepo, script, testCase)`.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/core/KeywordTestBase.java` · 35 LOC

Thin (35 LOC) bridge: loads the [[ObjectRepository]], reads the script via [[KeywordReader]], and hands the ordered `KeywordStep` list to [[KeywordEngine]] with the live driver from [[BaseTest]].

Used by `KeywordDrivenLoginTest` (saucedemo), `KeywordDrivenTextBoxTest` (demoqa) and SAHMAT's `LoginAndForgotPasswordKeywordTest`, whose `@DataProvider` reads every `testCase` name from the CSV so **new scenarios need zero Java**.

## Connections

- **Depends on →** [[BaseTest]] · [[KeywordEngine]] · [[KeywordReader]] · [[ObjectRepository]]
- **Used by ←** [[SAHMAT]] · [[SauceDemo]] · [[DemoQA]]
- **Related ↔** [[Keyword-Driven Testing]]
