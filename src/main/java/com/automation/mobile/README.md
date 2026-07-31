# 📱 Mobile (Appium) Module

Android/iOS app testing under the same automation + Allure/Extent
reporting umbrella as the rest of this framework — same Page Object +
TestNG pattern, just pointed at a mobile driver instead of a browser one.
This module is additive: it doesn't touch or depend on anything in
`com.automation.sites` (the web/browser side), so working with mobile
tests never risks breaking a web test or vice versa.

## 📋 Table of Contents
- [Quick Start](#-quick-start)
- [Local Emulator Visibility — Genymotion + `wmctrl`](#-local-emulator-visibility--genymotion--wmctrl)
- [Real Device Walkthrough — Samsung Galaxy S24](#-real-device-walkthrough--samsung-galaxy-s24)
- [What's Here](#-whats-here)
- [Configuration — `mobile.properties`](#️-configuration--mobileproperties)
- [Adding Your Own App](#-adding-your-own-app)
- [Verified](#-verified)
- [Still Open](#-still-open)
- [Dependency](#-dependency)
- [Related Docs](#-related-docs)

## ⚡ Quick Start
```bash
# 1. Start the Appium server (separately installed: npm install -g appium)
#    and make sure an emulator/device is already up — `adb devices` should
#    list it. mvn test does NOT start either of these for you locally
#    (only the CI pipelines do, in their own dedicated setup steps) — if
#    you skip this, the test fails with SessionNotCreatedException /
#    ConnectException on 127.0.0.1:4723, not a test/code error.
appium
adb devices

# 2. Copy the example config and fill in real values
cp src/test/resources/config/mobile.properties.example \
   src/test/resources/config/mobile.properties
# edit mobile.device.name to match `adb devices`; for the built-in example
# below, leave mobile.app.path empty and set:
#   mobile.app.package=com.android.settings
#   mobile.app.activity=.Settings
# These two are NOT optional for the example to do anything visible: if
# neither mobile.app.path nor mobile.app.package/activity is set, Appium
# starts a session with no target app at all — it lands on whatever
# screen the device already had open (usually the home launcher) and
# every element lookup then fails with a confusing NoSuchElementError
# that looks unrelated to config. AppiumDriverFactory logs a clear
# warning when this happens, but it's easy to miss in the noise — set
# them properly (or pass -Dmobile.app.package=... -Dmobile.app.activity=...
# on the command line) instead of relying on the warning to catch it.

# 3. Run the example test — works against any Android emulator/device,
#    no app-under-test setup required
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml \
  -Dmobile.app.package=com.android.settings -Dmobile.app.activity=.Settings
```

## 👀 Local Emulator Visibility — Genymotion + `wmctrl`

If you're using Genymotion (or another emulator with its own player window)
rather than a headless AVD, being connected via `adb` doesn't mean its
window is visible — the emulator runs fine in the background either way,
you just won't see anything happen unless the player window is open and
in front. The whole example test (launch → find element → screenshot →
teardown) takes about 2-3 seconds end-to-end, so it's easy to miss if the
window isn't already up when `mvn test` starts.

Two ways to keep it visible, in order of convenience:

| Option | Setup | Trade-off |
|---|---|---|
| **"Always on Top"** | Right-click the Genymotion player's title bar → *Always on top* (or the ⋮ window menu) | Set once, works for every run afterward — but the window can still get covered if another app also runs as always-on-top |
| **Auto-focus with `wmctrl`** | Linux only. Install: `sudo apt install wmctrl`. List window names: `wmctrl -l`. Focus it right before each run: `wmctrl -a "<name from wmctrl -l>"` | Needs one extra command per run, but reliably yanks the window to the front even if something else stole focus |

Typical `wmctrl` one-liner, chained before the Maven command so the window
is guaranteed focused when the test starts:
```bash
# 1. Find the exact window title Genymotion registered (varies by device profile)
wmctrl -l
# e.g. output: 0x02c00003  0 hostname   Google Pixel 7 - 13.0 - API 33 - 1080x2400

# 2. Focus it, then immediately run the test
wmctrl -a "Google Pixel 7 - 13.0" && \
  mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml \
    -Dmobile.app.package=com.android.settings -Dmobile.app.activity=.Settings
```
If `wmctrl -a` can't find a match, it fails silently (no error, window just
doesn't focus) — double-check the title against `wmctrl -l` verbatim,
including capitalization; a partial substring match is fine, but it has to
be a substring of what `-l` actually printed, not the device name you gave
it in Genymotion's UI.

`wmctrl` only affects window focus on the host machine — it's cosmetic for
watching the run, and has no effect on the Appium session itself. Skip it
entirely in CI or on a headless AVD; there's no window to focus.

## 📱 Real Device Walkthrough — Samsung Galaxy S24

Everything above (Appium server, `mobile.properties`, the example test)
works identically against a real Galaxy S24 — you're just replacing "start
an emulator" with "plug in and authorize a physical phone." No `wmctrl` /
Genymotion visibility trick is needed here: the phone's own screen is
always visible, so you'll see the Settings app launch and get driven live.

### 1. Turn on Developer Options + USB debugging on the S24
1. **Settings → About phone → Software information → tap "Build number" 7 times** — this unlocks Developer Options (One UI, same on Android 14/15).
2. **Settings → Developer options → toggle "USB debugging" on.**
3. Also worth enabling: **"Stay awake"** (screen doesn't sleep while charging/plugged into USB) so the session doesn't time out mid-test.

### 2. Connect and authorize
```bash
# Plug the S24 in via USB-C, then:
adb devices
# First time, the phone shows an "Allow USB debugging?" dialog —
# tap Allow (and "Always allow from this computer" so it doesn't ask again).
# A correctly authorized device looks like:
#   R5CTA0ABCDE    device
# If it instead shows "unauthorized", check the phone screen for the dialog —
# it's easy to miss behind other windows.
```

### 3. (Optional) Go wireless — skip the USB cable after pairing
```bash
# Android 11+ (S24 qualifies) supports wireless debugging natively:
# Settings → Developer options → Wireless debugging → toggle on
#   → "Pair device with pairing code" shows an IP:port + 6-digit code

adb pair 192.168.1.42:41253      # IP:port + code shown on the phone
# enter the 6-digit pairing code when prompted

adb connect 192.168.1.42:5555    # the (different) connection port shown
                                 # on the main Wireless debugging screen
adb devices                      # should now list the phone over Wi-Fi
```
Both the S24 and the machine running `mvn test` must be on the same Wi-Fi
network for this to work.

### 4. Configure `mobile.properties` for the S24
```properties
mobile.platform=android
appium.server.url=http://127.0.0.1:4723
mobile.device.name=R5CTA0ABCDE
# ^ exact serial from `adb devices` — over Wi-Fi this becomes the
#   ip:port pair instead, e.g. 192.168.1.42:5555
mobile.platform.version=14
# One UI 6.1 ships on Android 14; One UI 7 (Android 15) is the OTA update —
# check Settings → About phone → Software information → Android version
mobile.app.package=com.android.settings
mobile.app.activity=.Settings
mobile.noReset=false
mobile.timeout=15
mobile.newCommandTimeout=120
```

### 5. Run it
```bash
appium
# separate terminal:
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml \
  -Dmobile.app.package=com.android.settings -Dmobile.app.activity=.Settings
```
Appium's `UiAutomator2Driver` (the default for `mobile.platform=android`)
talks to the S24 exactly the same way it talks to an emulator — same
`AppiumDriverFactory`, same `SettingsHomePage`/`SettingsHomeTest`, no code
changes. The only difference end-to-end is `mobile.device.name` pointing
at a real serial/IP instead of an emulator name like `emulator-5554`.

### Notes specific to the S24
- **One UI adds an extra permission dialog** the first time a fresh
  `mobile.app.path` install requests something (camera, storage, etc.) —
  the built-in Settings example doesn't hit this, but a real app under
  test might; handle it as an extra step in the flow, or set
  `mobile.noReset=true` once you've dismissed it manually the first time.
- **Screen lock**: keep the S24 unlocked (or set a swipe-only lock, no
  PIN) while running tests — Appium can wake the screen but won't enter a
  PIN/pattern for you.
- **Battery optimization**: if a long regression run gets killed mid-way,
  check **Settings → Apps → \<your app\> → Battery → Unrestricted** — One
  UI aggressively backgrounds apps by default.

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

## ✅ Verified

- **Run against a real device**: confirmed end-to-end against a real
  emulator (Genymotion, Android 15/API 35) — `SettingsHomeTest` passes
  (session created, app launched via `appium:appPackage`/`appActivity`,
  element found, screenshot captured, clean teardown). The Settings-app
  example is a solid baseline, not just untested scaffolding.
- **Dependency resolves cleanly**: `io.appium:java-client:9.3.0` resolves
  and compiles fine against Maven Central — confirmed via
  `mvn dependency:resolve compile test-compile`.
- **Wired into all three CI pipelines**: `Jenkinsfile`'s `Mobile Test`
  stage, `.github/workflows/github-ci.yml`'s `mobile-test` job, and
  `.gitlab-ci.yml`'s `mobile-test` job all install/boot an emulator +
  Appium server and run `testng-suites/mobile-${SUITE_TYPE}.xml` with
  `-Dmobile.app.package=com.android.settings -Dmobile.app.activity=.Settings`.
  `mobile-smoke.xml`/`mobile-regression.xml` also now register
  `RetryListener` the same way every web suite does, so CI's
  `-Dretry.count` actually applies to mobile runs too (it silently didn't,
  before that was fixed).

## 🚧 Still open

- Only one screen (`SettingsHomePage`) is covered — extend with real screens once a target app is wired in.
- iOS (`IOSDriver`/`XCUITestOptions`) hasn't been run against a real simulator/device — only the Android path above is confirmed live.

## 📦 Dependency

`io.appium:java-client` (version `9.3.0`) was added to `pom.xml` for this — see the `<!-- Appium -->` comment there. Confirmed resolving and compiling cleanly against Maven Central (`mvn dependency:resolve compile test-compile`).

## 📚 Related docs

Main project README: `README.md` at the project root — see especially the "Mobile Testing (Appium)" section for a shorter overview, and "Specialized Testing" for accessibility/visual/perf, which are also opt-in test types outside the default web suites.
