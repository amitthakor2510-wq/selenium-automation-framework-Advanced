---
title: "SensitiveData"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "core/utils/SensitiveData.java"
loc: 46
---

# SensitiveData

> [!abstract] Keeps typed secrets out of Allure step names, logs and exception messages.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `core/utils/SensitiveData.java` · 46 LOC

`isSensitiveName(fieldName)` / `maskIfSensitive(...)`. Decision is by the *field name* (locator or object-repository key) — a deliberate heuristic (a field called `pw` slips through), hence `HumanActions.typeSecret()` for unconditional masking. Complements `${env:NAME}` placeholders in keyword CSVs ([[PlaceholderResolver]]). `SensitiveDataTest` covers it.

## Connections

- **Used by ←** [[HumanActions]] · [[KeywordEngine]]
- **Related ↔** [[PlaceholderResolver]] · [[Secret Scanning]]
