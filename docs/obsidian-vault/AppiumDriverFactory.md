---
title: "AppiumDriverFactory"
type: class
layer: mobile
tags: [fw/mobile, kind/class]
parent: "[[Mobile Appium Module]]"
source: "mobile/core/AppiumDriverFactory.java"
loc: 194
---

# AppiumDriverFactory

> [!abstract] Mobile counterpart of DriverFactory — creates `AndroidDriver`/`IOSDriver` from `mobile.*` / `appium.*` config.

**Part of:** [[Mobile Appium Module]]  ·  **Layer:** Mobile / Appium  ·  **Source:** `mobile/core/AppiumDriverFactory.java` · 194 LOC

Same 'one place creates the driver' idea, retargeted at the Appium server URL. Also used by the mobile bug crawler.

## Connections

- **Used by ←** [[MobileBaseTest]] · [[MobileAppCrawler]]
- **Related ↔** [[DriverFactory]]
