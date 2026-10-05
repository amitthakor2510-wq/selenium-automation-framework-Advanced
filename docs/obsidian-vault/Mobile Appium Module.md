---
title: "Mobile Appium Module"
type: hub
layer: mobile
tags: [fw/mobile, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Mobile Appium Module

> [!abstract] Appium module (`com.automation.mobile`) mirroring the web architecture — driver factory, base page, base test — with a working Android Settings example.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Mobile / Appium

`-Dsite=mobile` with `config/mobile.properties` (copy from `.example`): `mobile.platform=android`, `appium.server.url=http://127.0.0.1:4723`, `mobile.device.name`, `mobile.app.package=com.android.settings`, `mobile.app.activity=.Settings`, `mobile.noReset`, `mobile.timeout=15`, `mobile.newCommandTimeout=120`.

```bash
npm install -g appium          # + uiautomator2 driver
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
```
CI: each pipeline has a dedicated mobile job — emulator via `reactivecircus/android-emulator-runner` (GitHub, KVM) or SDK + AVD boot (Jenkins/GitLab, serialized with `resource_group`). Known local issue: emulator `adbd` stall without AES-NI. Videos are not recorded (Appium has its own API).

## Connections

- **Depends on →** [[AppiumDriverFactory]] · [[BaseMobilePage]] · [[MobileBaseTest]] · [[Settings App Example]]
- **Related ↔** [[MobileAppCrawler]] · [[CI-CD Pipelines]] · [[Test Types and Suites]]
