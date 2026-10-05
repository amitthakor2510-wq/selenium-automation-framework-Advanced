---
title: "Automation Console"
type: tool
layer: infra
tags: [fw/infra, kind/tool]
parent: "[[Docker and Selenium Grid]]"
---

# Automation Console

> [!abstract] Zero-dependency local web UI (Python `http.server`, port 5057, Basic Auth) to toggle sites/tests and read the last run — no Java/Maven needed.

**Part of:** [[Docker and Selenium Grid]]  ·  **Layer:** Infra / Docker

Start: `DASHBOARD_PASSWORD=… python3 Scripts/dashboard/dashboard_server.py` or Docker `--profile dashboard` (refuses to start without a password).

| Panel | Backed by |
|---|---|
| Sites (+ presets from `presets.json`) | [[pipeline-config properties|pipeline-config.properties]] |
| Run only / Tests / Groups / bulk Enable-Disable | [[test-config properties|test-config.properties]] |
| Last run by class, coverage, healing, flaky, TIA tiles | `target/surefire-reports`, `coverage-summary.json`, `self-healing*`, `flaky-tests.json`, `tia/impact-summary.json` |
| Audit log + Undo | `target/dashboard-audit.jsonl` |
| Copy run command, snapshot export/import, raw file viewer, CSV export | `suites.py`, `snapshot.py` |

**Security:** HTTP Basic (`dashboard` user), IP lockout after repeated failures, same-origin JSON POST only, no HTTPS (use SSH tunnel/ngrok TLS). **Modules:** `dashboard_server.py` (443 LOC) · `config_files.py` · `results.py` · `audit.py` · `presets.py` · `snapshot.py` · `suites.py` · `static/{index.html,app.js,style.css}`. Edits change one line in place, preserving comments and file permissions.

## Connections

- **Depends on →** [[pipeline-config properties]] · [[test-config properties]] · [[Healing Report Output]] · [[TIA Reports and CLI]]
- **Related ↔** [[Test Types and Suites]] · [[Docker and Selenium Grid]] · [[CI Python Scripts]]
