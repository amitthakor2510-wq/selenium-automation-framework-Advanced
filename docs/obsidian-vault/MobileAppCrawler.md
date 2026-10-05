---
title: "MobileAppCrawler"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "mobile/crawler/MobileAppCrawler.java"
loc: 475
---

# MobileAppCrawler

> [!abstract] Appium counterpart of the site crawler — explores an already-launched app depth-first by tapping and navigating back.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `mobile/crawler/MobileAppCrawler.java` · 475 LOC

`mvn exec:java@mobile-bug-crawler -Pmobile-bug-crawler` via `MobileCrawlerCli`. Limits `crawler.mobile.maxScreens=30`, `maxDepth=4`, `maxElementsPerScreen=8`, `tapSettleMillis=800`, output `target/mobile-crawler`. `AiMobileScreenReviewer` reviews the UI-hierarchy XML; vision pass via `crawler.mobile.ai.vision.enabled`.

⚠ It **physically taps** controls — `crawler.mobile.avoidTextContains` (default `delete, logout, log out, sign out, uninstall, pay, purchase, buy, remove account, reset, submit, confirm`) skips destructive ones. Read its javadoc before pointing it at a real account.

## Connections

- **Depends on →** [[AppiumDriverFactory]] · [[AI Page Reviewers]] · [[AiVisionClient]]
- **Related ↔** [[SiteCrawler]] · [[Mobile Appium Module]]
