---
title: "Settings App Example"
type: site
layer: mobile
tags: [fw/mobile, kind/site]
parent: "[[Mobile Appium Module]]"
source: "mobile/sites/settings/pages/SettingsHomePage.java"
loc: 44
---

# Settings App Example

> [!abstract] `SettingsHomePage` + `SettingsHomeTest` — first real Appium test, against Android's built-in Settings app so it runs on any emulator.

**Part of:** [[Mobile Appium Module]]  ·  **Layer:** Mobile / Appium  ·  **Source:** `mobile/sites/settings/pages/SettingsHomePage.java` · 44 LOC

Package `com.android.settings`, activity `.Settings`; 2 tests; suites `mobile-smoke.xml`, `mobile-regression.xml`. Template for your own app: point `mobile.app.path` (or package/activity) at it and add screen objects extending `BaseMobilePage`.

## Connections

- **Depends on →** [[BaseMobilePage]] · [[MobileBaseTest]]
