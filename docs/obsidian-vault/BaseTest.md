---
title: "BaseTest"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "sites/core/BaseTest.java"
loc: 302
---

# BaseTest

> [!abstract] Parent of every site test — opens/closes the browser per test, owns the per-thread driver and runs API-created data cleanup.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `sites/core/BaseTest.java` · 302 LOC

```
@BeforeMethod setUp()   → ConfigReader.init, DriverFactory.createDriver, navigate to site `url`, MDC tag
@Test                   → your test
@AfterMethod  tearDown()→ quit driver, clear ThreadLocal; cleanupAfterMethod(...)
@AfterClass             → cleanupAfterClass(...)
```
- `ThreadLocal<WebDriver>` → every thread has its own browser (required for `parallel="classes"`).
- `@Listeners({TestListener.class})` lives **here**, not in suite XML (Allure ordering).
- **Shared-session classes** (a full E2E that stays logged in, e.g. `BookStoreApplicationTest`) override `setUp()/tearDown()` to no-ops and drive the browser from `@BeforeClass/@AfterClass`.
- `cleanupAfterMethod/Class` register undo actions on a [[CleanupRegistry]] (LIFO, failures logged not thrown), independent of subclasses that override `tearDown()`.
- `runMethodCleanup / runClassCleanup / getDriver()` are the public surface.

## Connections

- **Depends on →** [[DriverFactory]] · [[ConfigReader]] · [[CleanupRegistry]]
- **Used by ←** [[KeywordTestBase]] · [[DemoQA]] · [[SauceDemo]] · [[SAHMAT]] · [[Template Scaffold]]
- **Related ↔** [[TestListener]] · [[RetryAnalyzer]] · [[MobileBaseTest]] · [[BaseApiTest]]
