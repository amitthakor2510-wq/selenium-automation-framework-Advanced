---
title: "BasePage"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/base/BasePage.java"
loc: 163
---

# BasePage

> [!abstract] Parent of every Page Object — shared driver/wait plumbing, CAPTCHA auto-hook, healing-aware waits and debug dumps. Includes the tiny `DriverProvider` bridge.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/base/BasePage.java` · 163 LOC

| Method | Purpose |
|---|---|
| `BasePage(WebDriver)` | stores driver + `WebDriverWait` |
| `navigateTo(path)` | `driver.get(...)` **then** `handleCaptchaIfPresent()` |
| `handleCaptchaIfPresent()` | no-throw, no-config detect-and-solve via [[CaptchaSolver]] |
| `waitVisible / waitClickable` | explicit waits routed through [[SelfHealingEngine]] |
| `getText / isDisplayed` | safe readers |
| `scrollAndJsClick(By/WebElement)` | scroll-into-view + JS click |
| `dumpPageForDebugging(label)` | writes HTML to `target/debug-dumps/` ([[PageHelperUtils]]) |

`DriverProvider` (8 LOC) is the thread-safe accessor Page Objects use to reach the active driver without owning it, keeping the ThreadLocal browser a single source of truth.

## Connections

- **Depends on →** [[SelfHealingEngine]] · [[CaptchaSolver]] · [[PageHelperUtils]] · [[HumanActions]]
- **Used by ←** [[DemoQA]] · [[SauceDemo]] · [[Template Scaffold]] · [[BootstrapModalComponent]] · [[ReactDatePickerComponent]]
- **Related ↔** [[Conventions and Troubleshooting]] · [[BaseTest]]
