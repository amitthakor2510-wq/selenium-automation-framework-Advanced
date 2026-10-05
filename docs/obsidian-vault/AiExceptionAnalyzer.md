---
title: "AiExceptionAnalyzer"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "core/ai/AiExceptionAnalyzer.java"
loc: 121
---

# AiExceptionAnalyzer

> [!abstract] Asks the model to classify why a test failed and suggest a next step — attached to Allure; never changes outcome.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/ai/AiExceptionAnalyzer.java` · 121 LOC

Wired into `TestListener.attachFailureDiagnostics()`. Input: exception, stack trace, page URL, trimmed page source, console logs. Output categories like locator-drift / timing-flakiness / application-bug / data-mismatch, attached as *AI Root-Cause Analysis — <test>*. Result type `AiFailureAnalysis`. Enable with `ai.exceptionAnalysis.enabled=true`.

## Connections

- **Depends on →** [[OllamaClient]] · [[FailureDiagnostics]]
- **Used by ←** [[TestListener]]
- **Related ↔** [[Allure Report]]
