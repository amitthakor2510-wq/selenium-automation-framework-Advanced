---
title: "Docker and Selenium Grid"
type: hub
layer: infra
tags: [fw/infra, kind/hub]
parent: "[[Selenium Framework Hub]]"
---

# Docker and Selenium Grid

> [!abstract] Self-hosted Selenium Grid 4.21 (hub + Chrome/Firefox/Edge nodes) with live noVNC view, an autoscaler and the test-runner image.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Infra / Docker

`docker-compose.yml` services: `selenium-hub`, `chrome`, `firefox`, `edge`, `tests` (images `selenium/hub|node-*:4.21.0`).

```bash
docker compose up -d
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml -Dgrid.enabled=true   # or GRID_ENABLED env
# watch a node (VNC password: secret) · hub console http://localhost:4444/ui/  (trailing slash!)
docker compose down
```
- **Dockerfile** — multi-stage Maven/Temurin 17 image; installs native Tesseract for CAPTCHA; drives *remote* browsers (no browser inside the image).
- **docker-compose.override.yml** — port-conflict fix using the `!reset` merge tag.
- **docker-compose.autoscale.yml** — `chrome-pool`/`firefox-pool`/`edge-pool`, scaled by [[grid-autoscaler script|grid-autoscaler.py]] polling the hub GraphQL `sessionQueueRequests`.
- **docker-compose.dashboard.yml + Dockerfile.dashboard** — the [[Automation Console]] behind the `dashboard` profile.
- Parallelism: suites' `thread-count` matches `SE_NODE_MAX_SESSIONS`.

## Connections

- **Depends on →** [[Selenium Grid]] · [[grid-autoscaler script]] · [[Automation Console]]
- **Related ↔** [[DriverFactory]] · [[CI-CD Pipelines]] · [[CaptchaSolver]]
