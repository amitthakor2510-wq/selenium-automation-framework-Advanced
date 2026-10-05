---
title: "KeywordEngine"
type: class
layer: keyword
tags: [fw/keyword, kind/class]
parent: "[[Keyword-Driven Testing]]"
source: "core/keyword/KeywordEngine.java"
loc: 425
---

# KeywordEngine

> [!abstract] Executes an ordered `List<KeywordStep>` against a live driver, resolving locators through the ObjectRepository and healing automatically.

**Part of:** [[Keyword-Driven Testing]]  ·  **Layer:** Keyword-driven  ·  **Source:** `core/keyword/KeywordEngine.java` · 425 LOC

- `run()` wraps **every** step exception in `KeywordExecutionException` so the failing step is named (hence [[RetryAnalyzer]]'s cause-chain logic).
- Element resolution goes through [[SelfHealingEngine]] — every keyword locator is self-healing with no script change.
- **Auto CAPTCHA**: after each `NAVIGATE` it calls [[CaptchaSolver]]'s detect/solve pass (`captcha.autoDetect.enabled`).
- `WAIT_FOR_PAGE_LOAD` polls `document.readyState=='complete'` (`pageLoad.timeout`, overridable per step in `testData`).
- Typed text is masked by [[SensitiveData]] in logs/step messages.
- `SCREENSHOT` step uses [[ScreenshotUtil]].

## Connections

- **Depends on →** [[Keyword]] · [[KeywordReader]] · [[ObjectRepository]] · [[PlaceholderResolver]] · [[SelfHealingEngine]] · [[CaptchaSolver]] · [[SensitiveData]] · [[ScreenshotUtil]] · [[PageHelperUtils]] · [[Framework Exceptions]]
- **Used by ←** [[KeywordTestBase]]
- **Related ↔** [[Keyword-Driven Testing]]
