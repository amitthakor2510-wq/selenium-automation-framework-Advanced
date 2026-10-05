---
title: "PlaceholderResolver"
type: class
layer: keyword
tags: [fw/keyword, kind/class]
parent: "[[Keyword-Driven Testing]]"
source: "core/keyword/PlaceholderResolver.java"
loc: 99
---

# PlaceholderResolver

> [!abstract] Expands `${source:NAME}` placeholders (e.g. `${env:SAHMAT_EMAIL}`) in `testData`/`expected` so secrets never live in a CSV.

**Part of:** [[Keyword-Driven Testing]]  ·  **Layer:** Keyword-driven  ·  **Source:** `core/keyword/PlaceholderResolver.java` · 99 LOC

Resolved at execution time by [[KeywordEngine]]; an unresolved key raises `KeywordExecutionException`. 6 unit tests in `PlaceholderResolverTest`. Pairs with [[SensitiveData]] masking and [[Secret Scanning]].

## Connections

- **Used by ←** [[KeywordEngine]]
- **Related ↔** [[SensitiveData]]
