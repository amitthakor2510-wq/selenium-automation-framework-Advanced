---
title: "AiVisionClient"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "core/ai/AiVisionClient.java"
loc: 233
---

# AiVisionClient

> [!abstract] Shared image+text LLM client for visual bug review of crawler screenshots.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/ai/AiVisionClient.java` · 233 LOC

Used by the visual pass in both crawlers (`crawler.ai.vision.enabled`, `crawler.mobile.ai.vision.enabled`). Config namespace `ai.vision.*` (provider default `anthropic`, endpoint/key/model/timeout). Distinct from the CAPTCHA vision call in [[CaptchaSolver]].

## Connections

- **Used by ←** [[AI Page Reviewers]] · [[MobileAppCrawler]]
- **Related ↔** [[OllamaClient]]
