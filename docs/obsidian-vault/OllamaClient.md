---
title: "OllamaClient"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "core/ai/OllamaClient.java"
loc: 236
---

# OllamaClient

> [!abstract] Shared text-only LLM client for self-healing, root-cause analysis and the crawler reviewers.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/ai/OllamaClient.java` · 236 LOC

Ollama `/api/chat` by default (`ai.endpoint`, `ai.model`), or `ai.provider=anthropic` (`ai.apiKey` falls back to `ANTHROPIC_API_KEY`). `ai.timeout.seconds=60`. Every enabled call costs one network round trip, which is why each feature is opt-in and a missing server never slows or log-spams a default run.

## Connections

- **Used by ←** [[AiLocatorHealer]] · [[AiExceptionAnalyzer]] · [[AI Page Reviewers]] · [[SiteCrawler]]
- **Related ↔** [[AiVisionClient]]
