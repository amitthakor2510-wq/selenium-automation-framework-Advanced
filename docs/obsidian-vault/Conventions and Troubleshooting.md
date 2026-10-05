---
title: "Conventions and Troubleshooting"
type: hub
layer: quality
tags: [fw/quality, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Conventions and Troubleshooting

> [!abstract] House rules for Page Objects, tests, logging, config — plus the common-error cheat sheet.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Quality gates

## Page Objects
- Extend [[BasePage]]; constructor is `super(driver)` only. Locators are `private final By` fields; prefer `id` > `css` > `xpath`.
- Click/type via [[HumanActions]], waits via `waitVisible/waitClickable`; **never `Thread.sleep`**.
- Page Objects return data/booleans — **never `Assert.*`** (reusable by keyword steps).
- `SmartLocator` only for locators that have already broken once.

## Tests
- Extend [[BaseTest]]; build the Page Object in `@BeforeMethod` (driver not valid earlier).
- Every `@Test` needs `groups`. Use TestNG `Assert` only. Retry is automatic — never reference `RetryAnalyzer`.

## Logging
SLF4J API → Log4j2 backend. `private static final Logger logger = LoggerFactory.getLogger(<Class>.class)` (copy the class name exactly). **No `System.out`.** Config `log4j2.xml`, per-site file `target/logs/<site>.log`, `-Dlog.level=DEBUG`, MDC `[%X{test}]` tags every line.

## Before committing
`mvn test` and `mvn verify` (Checkstyle) → the `pre-commit` hook runs Checkstyle + gitleaks (`SKIP_HOOKS=1` to bypass).

## Frequent failures
Browser/driver mismatch · demoqa `200` vs `204` delete status · ReCaptcha rate-limit on `/register` (seed via API) · sticky ad banner intercepting clicks (navigate by URL) · React controlled inputs dropping keystrokes (native value setter) · markup drift (use debug dumps → [[PageHelperUtils]]).

## Connections

- **Related ↔** [[Template Scaffold]] · [[Maintenance Scripts]] · [[Quality Gates]]
