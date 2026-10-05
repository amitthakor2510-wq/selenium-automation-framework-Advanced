---
title: "Selenium Grid"
type: tool
layer: infra
tags: [fw/infra, kind/tool]
parent: "[[Docker and Selenium Grid]]"
---

# Selenium Grid

> [!abstract] Remote execution target — `DriverFactory` switches to `RemoteWebDriver` when `grid.enabled=true`.

**Part of:** [[Docker and Selenium Grid]]  ·  **Layer:** Infra / Docker

Only remote target today (cloud grids like BrowserStack/Sauce Labs are a deferred roadmap item). Notes: Grid nodes control headless themselves; Safari cannot run on the Linux Grid; file uploads use `LocalFileDetector`.

## Connections

- **Depends on →** [[DriverFactory]]
- **Related ↔** [[Roadmap]]
