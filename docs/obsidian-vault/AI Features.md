---
title: "AI Features"
type: hub
layer: ai
tags: [fw/ai, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# AI Features

> [!abstract] Three opt-in, report-only AI features sharing one text-LLM client and one `ai.*` config namespace (Ollama default, Anthropic optional).

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** AI + CAPTCHA

All **off by default**, **report-only or index-only**: nothing auto-edits source, auto-retries a test, or lets a model invent a locator.

| # | Feature | Flag | Class |
|---|---|---|---|
| 1 | AI-assisted self-healing (stage 3) | `self-healing.ai.enabled` | [[AiLocatorHealer]] |
| 2 | AI root-cause analysis on failure | `ai.exceptionAnalysis.enabled` | [[AiExceptionAnalyzer]] |
| 3 | AI bug crawler (web + mobile) | `crawler.ai.enabled`, `crawler.checklistFile`, `crawler.ai.vision.enabled` | [[SiteCrawler]], [[AI Page Reviewers]], [[MobileAppCrawler]] |

**Separate namespaces:** `ai.*` = text LLM ([[OllamaClient]]) · `ai.vision.*` = image LLM ([[AiVisionClient]]) · `captcha.ai.*` = CAPTCHA vision ([[CaptchaSolver]]). Use a different model per job.

**Model guidance:** Qwen-Coder family for local (`qwen2.5-coder:7b` default; larger Qwen3-Coder tag with a big GPU; `qwen3:8b` lighter). Hosted: `ai.provider=anthropic` + `ANTHROPIC_API_KEY`. Pull the model with `ollama pull` first — nothing auto-pulls.

## Connections

- **Depends on →** [[OllamaClient]] · [[AiVisionClient]] · [[AiExceptionAnalyzer]] · [[AiLocatorHealer]] · [[SiteCrawler]] · [[AI Page Reviewers]] · [[MobileAppCrawler]]
- **Related ↔** [[Self-Healing Locators]] · [[CAPTCHA Solving]] · [[global properties]]
