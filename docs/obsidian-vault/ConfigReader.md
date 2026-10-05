---
title: "ConfigReader"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Configuration System]]"
source: "core/config/ConfigReader.java"
loc: 166
---

# ConfigReader

> [!abstract] Three-layer configuration loader held per-thread — `global.properties` → `<site>.properties` → `-Dkey=value`.

**Part of:** [[Configuration System]]  ·  **Layer:** Core framework  ·  **Source:** `core/config/ConfigReader.java` · 166 LOC

- Active site from `-Dsite=` (default `demoqa`); first calls [[SiteRegistry]]`.validate(site)` so a missing piece fails fast with a precise message.
- **ThreadLocal state** — previously a shared `Properties` meant one thread's `reset()` wiped config under another thread (real race in parallel runs).
- API: `init`, `reset`, `get(key)`, `get(key, default)`, `getNonBlank`, `getInt`, `getBoolean`, `getActiveSite`.
- Gotcha (fixed once): `get(key, default)` only substitutes the default when the key is **absent**, not blank — `ai.vision.endpoint=` blank in the file silently beat the default.
- `-Dsite` remains JVM-wide; per-thread site selection would need a `setActiveSite()` writing the ThreadLocal.

## Connections

- **Depends on →** [[SiteRegistry]] · [[global properties]] · [[Site Config Files]]
- **Used by ←** [[DriverFactory]] · [[HumanActions]] · [[RetryAnalyzer]] · [[ApiClient]] · [[SelfHealingEngine]] · [[CaptchaSolver]] · [[OllamaClient]]
- **Related ↔** [[Configuration System]]
