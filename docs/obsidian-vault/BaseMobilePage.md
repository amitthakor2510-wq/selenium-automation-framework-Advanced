---
title: "BaseMobilePage"
type: class
layer: mobile
tags: [fw/mobile, kind/class]
parent: "[[Mobile Appium Module]]"
source: "mobile/core/BaseMobilePage.java"
loc: 102
---

# BaseMobilePage

> [!abstract] Mobile counterpart of BasePage — shared `wait`, `waitVisible/waitClickable`, plus an explicit `handleCaptchaIfPresent()`.

**Part of:** [[Mobile Appium Module]]  ·  **Layer:** Mobile / Appium  ·  **Source:** `mobile/core/BaseMobilePage.java` · 102 LOC

No auto-hook on construction (an app is already launched before any screen object exists), so screen objects call `handleCaptchaIfPresent()` after transitions that might show a CAPTCHA.

## Connections

- **Depends on →** [[CaptchaSolver]]
- **Related ↔** [[BasePage]]
