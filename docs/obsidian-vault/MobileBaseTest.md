---
title: "MobileBaseTest"
type: class
layer: mobile
tags: [fw/mobile, kind/class]
parent: "[[Mobile Appium Module]]"
source: "mobile/core/MobileBaseTest.java"
loc: 108
---

# MobileBaseTest

> [!abstract] Mobile counterpart of BaseTest — opens/closes the Appium driver per test.

**Part of:** [[Mobile Appium Module]]  ·  **Layer:** Mobile / Appium  ·  **Source:** `mobile/core/MobileBaseTest.java` · 108 LOC

Every Appium test class extends it. Registers the same listeners so Allure/Extent reporting is identical to web tests.

## Connections

- **Depends on →** [[AppiumDriverFactory]] · [[TestListener]]
- **Related ↔** [[BaseTest]]
