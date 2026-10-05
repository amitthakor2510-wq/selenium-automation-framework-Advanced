---
title: "CAPTCHA Solving"
type: hub
layer: ai
tags: [fw/ai, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# CAPTCHA Solving

> [!abstract] Automatic and keyword-driven CAPTCHA solving for text/math image CAPTCHAs — OCR with a segmentation pipeline, optional AI Vision, never reCAPTCHA/hCaptcha.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** AI + CAPTCHA

**Two modes, one class ([[CaptchaSolver]])**

| Mode | Where | How |
|---|---|---|
| 1 · Automatic | every Page Object | `BasePage.navigateTo()` → `handleCaptchaIfPresent()`; `KeywordEngine` after `NAVIGATE`; `BaseMobilePage.handleCaptchaIfPresent()` (explicit) |
| 2 · Explicit | keyword scripts | `SOLVE_TEXT_CAPTCHA`, `SOLVE_MATH_CAPTCHA`, `SOLVE_CAPTCHA_WITH_AI`, `SOLVE_TEXT_CAPTCHA_IF_PRESENT` |

**Setup:** native Tesseract required (Tess4J is JNI). Docker image installs it; Jenkins/GitHub/GitLab install-if-missing. Check `tesseract --version`. `tesseract.datapath` / `TESSDATA_PREFIX` auto-detected.

**Cannot solve:** Google reCAPTCHA and hCaptcha → `isKnownUnsolvableCaptchaPresent()` backs off with a warning. DemoQA `/register` can return "Please verify ReCaptcha!" under rate limiting — fix by seeding accounts via API, not OCR.

**Primary consumer:** [[SAHMAT]] (5-char alphanumeric CAPTCHA, AI Vision on, OCR cross-check off).

## Connections

- **Depends on →** [[CaptchaSolver]] · [[OCR Accuracy Pipeline]]
- **Related ↔** [[BasePage]] · [[KeywordEngine]] · [[SAHMAT]] · [[AI Features]]
