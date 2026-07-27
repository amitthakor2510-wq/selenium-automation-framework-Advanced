# Mobile (Appium) Module — Scaffold

This package is a starting point for bringing your Android app testing
(currently manual — PM Gati Shakti, ministry portals, etc.) under the same
automation + Allure/Extent reporting umbrella as the web framework.

## What's here
- `core/AppiumDriverFactory.java` — creates an `AndroidDriver`/`IOSDriver`
  from config, same single-factory pattern as `core.driver.DriverFactory`.
- `core/BaseMobilePage.java` — same shape as `core.base.BasePage`
  (constructor takes the driver, exposes `wait`, `waitVisible`/`waitClickable`
  helpers) so writing a screen object feels identical to a web Page Object.

## What's NOT here yet (needs your actual app to build)
- No screen objects — add them under `com.automation.mobile.sites.<app>.pages`,
  extending `BaseMobilePage`, once you have real element IDs/accessibility
  labels from the target app (pull these via `appium inspector` or
  `uiautomatorviewer`, same idea as browser DevTools for web).
- No test classes — mirror `sites/core/BaseTest.java`'s pattern: a
  `MobileBaseTest` that calls `AppiumDriverFactory.createDriver()` in
  `@BeforeMethod` and quits it in `@AfterMethod`.
- No TestNG suite XML / CI wiring — deliberately left out until there's a
  real app driving real assertions; wiring an empty suite into CI would just
  be dead weight.

## To get this running
1. `npm install -g appium && appium` (or point at a remote Appium grid).
2. Copy `src/test/resources/config/mobile.properties.example` to
   `mobile.properties`, fill in your `.apk` path or package/activity.
3. `AppiumDriverFactory.createDriver()` gives you a `RemoteWebDriver` —
   from there it's the same Page Object + TestNG + Allure workflow already
   used for demoqa/saucedemo, just with mobile locators (resource-id,
   accessibility-id, or UiSelector via `AppiumBy`) instead of CSS/XPath.

## Dependency
`io.appium:java-client` was added to `pom.xml` for this — see the
`<!-- Appium -->` comment there for the pinned version.
