---
title: "Core Framework"
type: hub
layer: core
tags: [fw/core, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Core Framework

> [!abstract] Shared, never-site-specific engine under `com.automation.core` — base classes, config, driver factory, utilities and reusable widgets.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Core framework

83 main-source files. Grouped by responsibility:

| Package | Key classes | Hub |
|---|---|---|
| `base` | [[BasePage]] (+ `DriverProvider`) | — |
| `config` | [[ConfigReader]], [[SiteRegistry]] | [[Configuration System]] |
| `driver` | [[DriverFactory]] | — |
| `utils` | [[HumanActions]], [[SmartLocator]], [[ScreenshotUtil]], [[FailureDiagnostics]], [[VideoRecorder]], [[SensitiveData]], [[PageHelperUtils]], [[AccessibilityUtils]], [[VisualRegressionUtils]], [[CaptchaSolver]] | — |
| `components` | [[BootstrapModalComponent]], [[ReactDatePickerComponent]] | — |
| `data` | [[DataProvider]], [[DataRow]], [[Data File Readers]], [[SyntheticDataGenerator]] | [[Data-Driven Testing]] |
| `keyword` | [[KeywordEngine]] and friends | [[Keyword-Driven Testing]] |
| `selfhealing` | [[SelfHealingEngine]] and friends | [[Self-Healing Locators]] |
| `ai` / `crawler` | [[OllamaClient]], [[SiteCrawler]] … | [[AI Features]] |
| `tia` / `coverage` | [[TestImpactAnalyzer]] … | [[Test Impact Analysis]] |
| `report` | [[ExtentManager]], [[AllureEnvironmentWriter]] | [[Reporting and Observability]] |
| `retention` | [[ArtifactRetentionCleaner]] | — |
| `exceptions` | [[Framework Exceptions]] | — |

**Three chokepoints** that make "every Page Object gets feature X for free" true: `BasePage.waitVisible/waitClickable`, `HumanActions.click/type` and `KeywordEngine` element resolution — all three route through [[SelfHealingEngine]].

## Connections

- **Related ↔** [[Architecture and Test Lifecycle]] · [[Project Structure]]
