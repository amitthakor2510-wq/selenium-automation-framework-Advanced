---
title: "AiLocatorHealer"
type: class
layer: healing
tags: [fw/healing, kind/class]
parent: "[[Self-Healing Locators]]"
source: "core/selfhealing/AiLocatorHealer.java"
loc: 207
---

# AiLocatorHealer

> [!abstract] Stage 3 last resort: shows a text LLM a numbered pool of real candidates and asks it to pick **by index only**.

**Part of:** [[Self-Healing Locators]]  ·  **Layer:** Self-healing  ·  **Source:** `core/selfhealing/AiLocatorHealer.java` · 207 LOC

Safety property: the model can never invent a selector — an out-of-range answer cannot point anywhere but an element Selenium already resolved. Accepted only if confidence ≥ `self-healing.ai.confidence` (0.6) **and** the element is still displayed/enabled on return. Uses [[OllamaClient]]. Tagged `ai` in the heal report.

## Connections

- **Depends on →** [[OllamaClient]]
- **Used by ←** [[SelfHealingEngine]]
- **Related ↔** [[AI Features]]
