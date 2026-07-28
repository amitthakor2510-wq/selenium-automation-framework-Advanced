# 📱 Mobile (Appium) Module

Android/iOS app testing under the same automation + Allure/Extent
reporting umbrella as the rest of this framework — same Page Object +
TestNG pattern, just pointed at a mobile driver instead of a browser one.
This module is additive: it doesn't touch or depend on anything in
`com.automation.sites` (the web/browser side), so working with mobile
tests never risks breaking a web test or vice versa.

## ⚡ Quick Start
```bash
# 1. Start the Appium server (separately installed: npm install -g appium)
appium

# 2. Copy the example config and fill in real values
cp src/test/resources/config/mobile.properties.example \
   src/test/resources/config/mobile.properties
# edit mobile.device.name to match `adb devices`; for the built-in example
# below, leave mobile.app.path empty and set:
#   mobile.app.package=com.android.settings
#   mobile.app.activity=.Settings

# 3. Run the example test — works against any Android emulator/device,
#    no app-under-test setup required
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
```

## 🧩 What's here

| File | Web equivalent | Role |
|---|---|---|
| `core/AppiumDriverFactory.java` | `core.driver.DriverFactory` | Creates an `AndroidDriver`/`IOSDriver` from config — same single-factory pattern |
| `core/BaseMobilePage.java` | `core.base.BasePage` | Constructor takes the driver, exposes `wait`, `waitVisible`/`waitClickable` helpers — writing a screen object feels identical to a web Page Object |
| `src/test/java/.../mobile/core/MobileBaseTest.java` | `sites/core/BaseTest` | Creates the Appium driver in `@BeforeMethod`, quits it in `@AfterMethod`, implements `DriverProvider` so the existing `TestListener` (screenshot-on-failure, Allure/Extent attachments) works unmodified for mobile tests too |
| `sites/settings/pages/SettingsHomePage.java` + `.../settings/tests/SettingsHomeTest.java` (under `src/test/java`) | a `LoginPage` + `LoginTest` | Working example against Android's **built-in Settings app** — runs end-to-end on any emulator/device with zero app-under-test setup, the same way demoqa/saucedemo let you try the web module with just a browser. Use as the template for a real app |
| `testng-suites/mobile-smoke.xml` / `mobile-regression.xml` | `demoqa-smoke.xml` / `-regression.xml` | Scan `com.automation.mobile.sites.*` the same way the web suites scan `com.automation.sites.<site>.tests` |

## ⚙️ Configuration — `mobile.properties`

Copied from `mobile.properties.example` (see Quick Start above). Loaded the same way `demoqa.properties`/`saucedemo.properties` are — `ConfigReader` reads `config/<site>.properties` for whatever `-Dsite` you pass, so this file just needs to exist and be run under `-Dsite=mobile`. No `ConfigReader` code changes needed, and any key below can still be overridden per-run with `-D<key>=<value>`.

| Key | Meaning | Example |
|---|---|---|
| `mobile.platform` | `android` or `ios` | `android` |
| `appium.server.url` | Where the Appium server is listening | `http://127.0.0.1:4723` |
| `mobile.device.name` | Emulator/simulator name, or a real device's `adb devices`/`xcrun simctl` name | `emulator-5554` |
| `mobile.platform.version` | OS version on the target device (optional) | `14` |
| `mobile.app.path` | Fresh install from a local `.apk`/`.ipa` build artifact | *(leave empty if using package/activity below)* |
| `mobile.app.package` / `mobile.app.activity` | Target an already-installed app instead (Android only) | `com.android.settings` / `.Settings` |
| `mobile.noReset` | Keep app data between sessions instead of a clean reinstall each run | `false` |
| `mobile.timeout` | Explicit wait timeout in seconds, used by `BaseMobilePage`'s `waitVisible`/`waitClickable` | `15` |
| `mobile.newCommandTimeout` | Seconds Appium waits for the next command before killing the session | `120` |

Point `appium.server.url` at a remote/cloud grid (BrowserStack, Sauce Labs, a self-hosted grid) instead of a local Appium server the same way — just change the URL.

## ➕ Adding your own app

1. Add screen objects under `com.automation.mobile.sites.<app>.pages`, extending `BaseMobilePage`, using real element IDs/accessibility labels pulled via `appium inspector` or `uiautomatorviewer` (the mobile equivalent of browser DevTools).
2. Add test classes under `com.automation.mobile.sites.<app>.tests`, extending `MobileBaseTest` — same Page Object + TestNG + Allure pattern as `SettingsHomeTest`.
3. Point `mobile.app.path` (fresh install from a local `.apk`/`.ipa`) or `mobile.app.package` + `mobile.app.activity` (already-installed app) at your app in `mobile.properties`.
4. Run it: `mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/<your-suite>.xml` (copy `mobile-smoke.xml` as a starting point — swap the `<package>` to your new test package if you don't want it lumped in with the Settings example).

`AppiumDriverFactory.createDriver()` gives you a `RemoteWebDriver` — from there it's the same Page Object + TestNG + Allure workflow already used for demoqa/saucedemo, just with mobile locators (resource-id, accessibility-id, or a `UiSelector` string via `AppiumBy`) instead of CSS/XPath.

## 🚧 Still open

- Only one screen (`SettingsHomePage`) is covered — extend with real screens once a target app is wired in.
- Not yet wired into `github-ci.yml` / `.gitlab-ci.yml` / `Jenkinsfile` — those all assume a browser + `WebDriverManager`, not an Appium server + emulator/device. Add a dedicated mobile job/stage (with an Android emulator step, e.g. `reactivecircus/android-emulator-runner` on GitHub Actions) when you're ready to run this in CI rather than locally.
- `AndroidDriver`/`IOSDriver` haven't been run against a real device/app in the environment that built this module (no Appium server or Android SDK available there) — the Settings-app example above is standard Appium/UiAutomator2 usage, but verify it against a live emulator before treating it as your baseline.

## 📦 Dependency

`io.appium:java-client` (version `9.3.0`) was added to `pom.xml` for this — see the `<!-- Appium -->` comment there. Same build-verification caveat as the rest of the module: run `mvn dependency:resolve` locally to confirm it resolves cleanly, since it hasn't been checked against Maven Central in the environment that added it.

## 📚 Related docs

Main project README: `README.md` at the project root — see especially the "Mobile Testing (Appium)" section for a shorter overview, and "Specialized Testing" for accessibility/visual/perf, which are also opt-in test types outside the default web suites.
