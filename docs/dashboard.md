<div align="center">

# 🖥️ Automation Console (Dashboard)

</div>

---

## 📋 Table of Contents
- [🚀 Starting It](#-starting-it)
- [🔐 Authentication](#-authentication)
- [🌍 Sharing It (ngrok / SSH tunnel / LAN)](#-sharing-it-ngrok--ssh-tunnel--lan)
- [🎛️ What It Does](#️-what-it-does)
- [🎯 Presets](#-presets)
- [📦 Bulk Section Actions](#-bulk-section-actions)
- [🏷️ Groups](#️-groups)
- [🩺 Results Drilldown](#-results-drilldown)
- [🕓 Audit Log & Undo](#-audit-log--undo)
- [📄 Raw File Viewer & CSV Export](#-raw-file-viewer--csv-export)
- [🚫 What It Deliberately Doesn't Do](#-what-it-deliberately-doesnt-do)
- [🧩 How It Works](#-how-it-works)
- [🩹 Troubleshooting](#-troubleshooting)

---

A small local web UI over the two things covered in [🎛️ Choosing Which Sites and Tests Run](configuration.md#️-choosing-which-sites-and-tests-run) plus the last local test run's results — one screen instead of hand-editing two properties files and digging through `target/`. Anyone on the team can use it without a Java/Maven setup: it's a standalone Python script with **zero third-party dependencies** (stdlib `http.server` only — the same convention `notify_chat.py` already uses in this repo, see its module docstring), so there's nothing to `pip install` and no build step for the frontend either (plain HTML/CSS/JS, no framework, no CDN — it has to work with no internet access, e.g. on an air-gapped CI runner or a LAN-only box).

---

## 🚀 Starting It

**Without Docker** (only needs Python 3.8+, nothing else):

```bash
DASHBOARD_PASSWORD=choose-a-password python3 Scripts/dashboard/dashboard_server.py
# open http://localhost:5057
```

**With Docker** (doesn't need Python installed locally either):

```bash
DASHBOARD_PASSWORD=choose-a-password \
  docker compose -f docker-compose.yml -f docker-compose.dashboard.yml \
  --profile dashboard up dashboard
# open http://localhost:5057
```

The Docker service bind-mounts the whole repo read-write (`docker-compose.dashboard.yml`), so it edits the exact same `pipeline-config.properties` / `test-config.properties` / `target/` your host `mvn test` reads and writes — not a copy baked into the image. It is **not** part of the default Selenium Grid stack (`docker compose up` alone does not start it) — it lives behind the `dashboard` Compose profile and its own `-f` file, deliberately kept separate from `docker-compose.yml` the same way `docker-compose.autoscale.yml` is.

Change the port with `--port 5057` / `DASHBOARD_PORT` (script) or `DASHBOARD_HOST_PORT` (Docker, host side only — the container always listens on 5057 internally).

---

## 🔐 Authentication

HTTP Basic Auth, username `dashboard`. Two ways to set the password:

- **`DASHBOARD_PASSWORD` set** — that's the password, browser prompts for it once and remembers it for the session.
- **`DASHBOARD_PASSWORD` unset** (running the script directly, not via Docker) — a random password is generated and printed to stdout in a boxed banner at startup. It changes every restart, which is fine for a quick local look but not for anything you'll come back to later — set the variable instead.

Docker Compose **refuses to start** without `DASHBOARD_PASSWORD` set (`environment: DASHBOARD_PASSWORD: ${DASHBOARD_PASSWORD:?...}` in `docker-compose.dashboard.yml`) — a container is far more likely to end up tunnelled or shared than a foreground terminal, so there's no silent-random-password fallback there.

12 failed attempts from the same IP within 60 seconds locks that IP out for the rest of the window, correct password included — a basic brake against someone hammering a shared/tunnelled URL, not a substitute for a real password.

---

## 🌍 Sharing It (ngrok / SSH tunnel / LAN)

The dashboard listens on `0.0.0.0` by default so it's reachable from other machines, but treat the link like any other credential-protected internal tool:

- **ngrok:** `ngrok http 5057`, then share the ngrok URL — the Basic-Auth prompt still applies on top of it. Set a real `DASHBOARD_PASSWORD` first; don't rely on the random generated one when tunnelling, since anyone who has the URL now also needs the exact password from your terminal at that moment.
- **SSH tunnel** (no public exposure at all): `ssh -L 5057:localhost:5057 user@the-box`, then open `http://localhost:5057` on your own machine.
- **LAN:** anyone on the same network can already reach `http://<that-machine's-IP>:5057` — same auth applies.

It is deliberately not HTTPS (plain HTTP, stdlib `http.server`) — fine on `localhost` or over an SSH tunnel, but a Basic-Auth password sent over plain LAN or ngrok's HTTP is only as private as that network. ngrok's own tunnel is TLS-terminated at ngrok's edge, which covers the public leg; prefer HTTPS everywhere it's an option for anything beyond a quick local look.

---

## 🎛️ What It Does

| Panel | Backed by | What it does |
|---|---|---|
| **Sites** | `pipeline-config.properties` | One switch per site (`demoqa`, `saucedemo`, `mobile`, `SAHMAT`, `jsonplaceholder`) |
| **Run only** | `run.only` in `test-config.properties` | Restrict the next run to a comma-separated list of classes |
| **Tests** | `test-config.properties` | One switch per test class, grouped into the same collapsible sections the file itself uses (`# ── ... ──` headers); a filter box to find one class fast |
| **Last run — by class** + the summary tiles at the top | `target/surefire-reports`, `target/coverage-summary.json` (or `jacoco.xml` directly), `target/self-healing*`, `target/flaky-tests.json`, `target/tia/impact-summary.json` | Pass/fail/skip counts, coverage %, self-healed locator count, flaky count, and a per-class breakdown — from whatever the *last local run* already produced. Refreshes automatically every 15s, or hit **Refresh**. |

Every toggle writes through the exact same `test.<Class>.enabled=` / `site.<name>.enabled=` / `run.only=` lines documented in [🎛️ Choosing Which Sites and Tests Run](configuration.md#️-choosing-which-sites-and-tests-run) — it edits the single matching line in place (comments and everything else in the file untouched), the same file `mvn test` reads next time you run it. No dashboard restart needed for a change to take effect; the properties files aren't cached beyond a single request.

---

## 🎯 Presets

The **Site presets** panel applies a named combination of site switches in one click — e.g. "demoqa only", "API only" — defined in [`Scripts/dashboard/presets.json`](../Scripts/dashboard/presets.json), a plain JSON file you can edit or extend without touching any Python. Ships with the combination this doc's own earlier Q&A walked through by hand ("Everything except demoqa & saucedemo") plus the common single-site and API-only shapes.

Presets are **site-level only, on purpose** — a site is a simple independent on/off switch, so combining several is unambiguous. A test-level "smoke only" preset would be misleading: a TestNG test can carry several groups at once (e.g. `{"smoke", "regression"}`), and disabling one group disables the whole test — so a naive "smoke only" preset could silently also switch off smoke tests that happen to carry another group too. That's why test-level bulk changes are exposed differently — see the next section.

A preset that references a site no longer in `pipeline-config.properties` (renamed/removed) skips that entry rather than creating a stray new line, and tells you which one it skipped.

## 📦 Bulk Section Actions

Each section header in the **Tests** panel has **Enable all** / **Disable all** buttons — they act on exactly the class list already shown under that header (the same sections `test-config.properties` groups tests into), written as one atomic file change. Deterministic on purpose: it names the exact classes on screen, rather than trying to interpret a semantic label like "the API tests."

## 🏷️ Groups

The **Groups** panel lists every `group.<name>.enabled=false` line already in `test-config.properties` and lets you switch one back on, or add a new group to disable by name (e.g. `perf`, `accessibility`, `visual`) — the same mechanism `test-config.properties`'s own commented-out examples show. A test carrying a disabled group is switched off regardless of its other groups or its own `test.<Class>.enabled` line — see [🎛️ Choosing Which Sites and Tests Run](configuration.md#️-choosing-which-sites-and-tests-run) for the exact precedence rules.

## 🩺 Results Drilldown

Below the summary tiles and the per-class table, two more panels expand on what only showed as a count before:

- **Self-healed locators** — every locator the self-healing engine actually had to fall back on in the last run (original → healed, which site, which stage — DOM/visual/AI), from `target/self-healing/*-healing-report.json`. A locator showing up here repeatedly is worth fixing at the source rather than leaving to self-heal every run.
- **Flaky tests** — from `target/flaky-tests.json` (produced by the `compute_flaky_trend.py` CI script against the retained run-history window on `gh-pages`, so this panel is usually empty on a pure-local checkout that has never run in CI). Each entry shows its pass/fail counts and the most recent statuses as a small strip of ticks.

The summary tiles also add a **TIA selected** count (`impacted / total` test classes) when `target/tia/impact-summary.json` exists from a local Test Impact Analysis run.

## 🕓 Audit Log & Undo

Every change made *through the dashboard* — a site toggle, a test toggle, a bulk section action, a preset, a group change, an edit to `run.only` — is appended to `target/dashboard-audit.jsonl` (already gitignored, since `target/` is build output) and shown newest-first in the **Recent changes** panel: when, who (the client IP — there's no login system beyond the shared password, so that's the most this tool can identify), and what changed. It only records edits made through this dashboard's own API; a change made by hand in a text editor, or via `-Dtests.disabled=...` on the command line, leaves no trace here.

**Undo last change** reverts the single most recent entry back to its recorded previous value. Only single-value changes (one site, one test, one group, `run.only`) can be undone this way — a bulk-section or preset change touched several keys at once with no single "previous value" to restore to, so Undo declines those with a clear error rather than guessing.

## 📄 Raw File Viewer & CSV Export

- **View raw file** (under the Sites and Tests panels) opens the exact current text of `pipeline-config.properties` / `test-config.properties` in a read-only modal — useful to sanity-check that a toggle landed where you expected, or just to read the file's own header comments without leaving the browser.
- **Export CSV** (under the results table) downloads the current per-class pass/fail/skip breakdown as a `.csv` — generated client-side from data already on screen, no extra request.

---

## 🚫 What It Deliberately Doesn't Do

**There is no "Run tests" button.** The dashboard only ever reads `target/` and edits the two properties files — it never shells out to Maven, git, or anything else, and it never executes anything the browser sends it (see `dashboard_server.py`'s module docstring). Running `mvn test` from a web request is a materially different security posture than editing two config files — arbitrary-ish command execution reachable over a network, versus a handful of regex-validated property writes — and the second one is what this tool commits to. Kick off the actual run yourself (`mvn test ...`, a CI pipeline, Jenkins) after toggling what you want; hit **Refresh** once it finishes.

---

## 🧩 How It Works

- **`Scripts/dashboard/dashboard_server.py`** — the HTTP server (stdlib `http.server.ThreadingHTTPServer`), Basic Auth, and routing. No CLI flags beyond `--port`/`--host` (env vars `DASHBOARD_PORT`/`DASHBOARD_HOST` also work).
- **`Scripts/dashboard/config_files.py`** — line-precise read/write for both properties files (regex substitution on the one matching line — never a full parse-and-rewrite, so hand-written comments survive). All three setters (`set_site_enabled`, `set_test_enabled`, `set_run_only`) validate their input against a strict character allow-list before touching a file.
- **`Scripts/dashboard/results.py`** — read-only aggregation of `target/`, reusing `.github/workflows/scripts/_junit_utils.py`'s existing surefire-XML parser rather than a second implementation of the same parsing.
- **`Scripts/dashboard/static/`** — the frontend: `index.html` + `style.css` + `app.js`, no build step, no framework, no CDN.

---

## 🩹 Troubleshooting

| Symptom | Likely cause |
|---|---|
| Browser keeps re-prompting for a password | Wrong password, or that IP is locked out (12 failed attempts/60s) — wait a minute and retry |
| "No test results yet" | No `target/surefire-reports` — run `mvn test` (against any site) once, then **Refresh** |
| Coverage tile missing | No `target/coverage-summary.json` and no `target/site/jacoco/jacoco.xml` — run `mvn verify -Pjacoco-check` (or the CI coverage-gate job) at least once |
| A toggle doesn't seem to take effect | Confirm the dashboard's repo root (`/api/health`) matches the checkout your `mvn test` runs from — under Docker this is whatever `docker-compose.dashboard.yml`'s bind mount points at |
| `docker compose --profile dashboard up` starts nothing | The `dashboard` profile is opt-in on purpose — `--profile dashboard` must be passed explicitly, plain `docker compose up` skips it |
