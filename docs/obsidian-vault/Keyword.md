---
title: "Keyword"
type: class
layer: keyword
tags: [fw/keyword, kind/class]
parent: "[[Keyword-Driven Testing]]"
source: "core/keyword/Keyword.java"
loc: 88
---

# Keyword

> [!abstract] Enum of every action the engine can execute (26 values).

**Part of:** [[Keyword-Driven Testing]]  ·  **Layer:** Keyword-driven  ·  **Source:** `core/keyword/Keyword.java` · 88 LOC

See the vocabulary in [[Keyword-Driven Testing]]. Adding an action = add an enum value + a `case` in [[KeywordEngine]]`.execute()`. Includes the CAPTCHA keywords (`SOLVE_TEXT_CAPTCHA`, `SOLVE_MATH_CAPTCHA`, `SOLVE_CAPTCHA_WITH_AI`, `SOLVE_TEXT_CAPTCHA_IF_PRESENT`) and `WAIT_FOR_PAGE_LOAD`, which SAHMAT's dynamically rendered login relies on.

## Connections

- **Used by ←** [[KeywordEngine]] · [[KeywordReader]]
