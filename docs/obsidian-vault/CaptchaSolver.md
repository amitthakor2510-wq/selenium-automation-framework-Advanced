---
title: "CaptchaSolver"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[CAPTCHA Solving]]"
source: "core/utils/CaptchaSolver.java"
loc: 4022
---

# CaptchaSolver

> [!abstract] The 4,022-line CAPTCHA engine — detect, preprocess, segment, OCR, AI Vision and reconcile. Largest class in the repo (~13% of all LOC).

**Part of:** [[CAPTCHA Solving]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/utils/CaptchaSolver.java` · 4022 LOC

**Public API:** `detectCaptchaImage`, `detectCaptchaInputField`, `isKnownUnsolvableCaptchaPresent`, `autoSolveIfPresent`, `solveTextCaptcha`, `solveMathCaptcha`, `solveWithAI`.

**Detection:** common case-insensitive `id/class/name/alt/src` patterns containing `captcha` on `<img>/<canvas>` plus a nearby `<input>`; plain `List<By>` constants (`CAPTCHA_IMAGE_LOCATORS`, `CAPTCHA_INPUT_LOCATORS`, `UNSOLVABLE_CAPTCHA_LOCATORS`) to extend. Cheap (a few `findElements`), silent when absent, non-fatal.

**Routing:** `captcha.ai.enabled=true` → AI Vision first (Anthropic default or Ollama e.g. `llava` / `qwen2.5vl:7b`), OCR only if the call *fails*; `SOLVE_CAPTCHA_WITH_AI` always vision. With `captcha.ai.crossCheckWithOcr=true` the two answers are reconciled (`reconcileAiAndOcrAnswers`).

**Waits:** `captcha.wait.seconds`, `pageLoad.timeout`, `captcha.image.load.timeout=10` (waits for `img.complete && naturalWidth>0` — otherwise it screenshots a broken-image placeholder).

Recent maintenance (per notes): adaptive relaxation in `splitTouchingCharactersAdaptive()`, `cropWithPadding()` bleed fix, `trySplitByColumnValley()` column fix, `rebuildSegmentFromBinary()` bounding-box inheritance fix (case correction misfires).

## Connections

- **Depends on →** [[OCR Accuracy Pipeline]] · [[AiVisionClient]] · [[ConfigReader]]
- **Used by ←** [[BasePage]] · [[KeywordEngine]] · [[BaseMobilePage]] · [[SAHMAT]]
- **Related ↔** [[Keyword]]
