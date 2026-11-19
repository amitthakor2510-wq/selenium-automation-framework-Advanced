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
- [🧭 Console Layout, Overview & Command Palette](#-console-layout-overview--command-palette)
- [📦 Bulk Section Actions](#-bulk-section-actions)
- [🏷️ Groups](#️-groups)
- [🩺 Results Drilldown](#-results-drilldown)
- [🕓 Audit Log & Undo](#-audit-log--undo)
- [📄 Raw File Viewer & CSV Export](#-raw-file-viewer--csv-export)
- [📋 Copy Run Command](#-copy-run-command)
- [💾 Snapshot Export/Import](#-snapshot-exportimport)
- [🧙 Test Wizard](#-test-wizard)
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

> ⚠️ **The Docker dashboard cannot run tests.** `Dockerfile.dashboard` has no Java or Maven, so it only reads `target/` and edits config. Run tests with the bind-mounted compose setup (`docker compose run --rm tests`) or on the host (`mvn test` / `Scripts/start-dashboard.sh`), then **Refresh**. See [🖥️ Dashboard Image: Can It Run Tests?](../DOCKER.md#️-dashboard-image-can-it-run-tests).

Change the port with `--port 5057` / `DASHBOARD_PORT` (script) or `DASHBOARD_HOST_PORT` (Docker, host side only — the container always listens on 5057 internally).

---

## 🔐 Authentication

HTTP Basic Auth, username `dashboard`. Two ways to set the password:

- **`DASHBOARD_PASSWORD` set** — that's the password, browser prompts for it once and remembers it for the session.
- **`DASHBOARD_PASSWORD` unset** (running the script directly, not via Docker) — a random password is generated and printed to stdout in a boxed banner at startup. It changes every restart, which is fine for a quick local look but not for anything you'll come back to later — set the variable instead.

Docker Compose **refuses to start** without `DASHBOARD_PASSWORD` set (`environment: DASHBOARD_PASSWORD: ${DASHBOARD_PASSWORD:?...}` in `docker-compose.dashboard.yml`) — a container is far more likely to end up tunnelled or shared than a foreground terminal, so there's no silent-random-password fallback there.

10 failed attempts from the same IP within 60 seconds locks that IP out for the rest of the window, correct password included — a basic brake against someone hammering a shared/tunnelled URL, not a substitute for a real password.

---

## 🌍 Sharing It (ngrok / SSH tunnel / LAN)

The dashboard listens on `127.0.0.1` (this machine only) by default, because it can rewrite your test and pipeline config. To reach it from other machines, opt in explicitly: `--host 0.0.0.0` / `DASHBOARD_HOST=0.0.0.0` for the script, or `DASHBOARD_BIND_ADDRESS=0.0.0.0` for Docker (the published port is bound to `127.0.0.1` unless you set it). Basic Auth travels unencrypted over plain HTTP, so treat the link like any other credential-protected internal tool:

- **ngrok:** `ngrok http 5057`, then share the ngrok URL — the Basic-Auth prompt still applies on top of it. Set a real `DASHBOARD_PASSWORD` first; don't rely on the random generated one when tunnelling, since anyone who has the URL now also needs the exact password from your terminal at that moment.
- **SSH tunnel** (no public exposure at all): `ssh -L 5057:localhost:5057 user@the-box`, then open `http://localhost:5057` on your own machine.
- **LAN:** anyone on the same network can already reach `http://<that-machine's-IP>:5057` — same auth applies.

Write requests (every toggle, preset, undo and snapshot import) must be same-origin `application/json` POSTs — the dashboard's own page already sends them that way. A request with any other content type, or with an `Origin` header whose host differs from the `Host` header, is answered `403`; this stops an unrelated web page you have open in the same browser from silently flipping your test switches using the credentials the browser has cached for this site. Plain `curl` calls (no `Origin` header) still work as long as they send `-H 'Content-Type: application/json'`. If you put the dashboard behind a reverse proxy, make sure it forwards the original `Host` header. Only requests that actually send a wrong password count towards the 10-per-minute lockout; the browser's initial no-credentials request and the Docker healthcheck do not.

Edits keep the properties files' existing permissions and owner (including when the dashboard runs as root in Docker against the bind-mounted repo), so your host `mvn test` can still read and write them afterwards. Startup prints a warning if `DASHBOARD_PASSWORD` is shorter than 8 characters. Every response carries `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and a same-origin `Content-Security-Policy`, so the page cannot be framed by another site (clickjacking on the toggle buttons). A POST body that is larger than 1 MB, not valid JSON, or not a JSON object is rejected with `413`/`400` and changes nothing (it is never treated as an empty request), and `/api/snapshot/import` refuses an object that has none of `sites`/`tests`/`groups`/`run_only`.

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

Each section header in the **Tests** panel has **Enable all** / **Disable all** buttons — they act on exactly the class list already shown under that header (the same sections `test-config.properties` groups tests into), written as one atomic file change. **With the class filter active they act only on the matching classes**, and the confirmation says so ("Disable 3 matching tests…"), so a filtered view never changes tests you cannot see. Deterministic on purpose: it names the exact classes on screen, rather than trying to interpret a semantic label like "the API tests."

## 🏷️ Groups

The **Groups** panel lists every `group.<name>.enabled=false` line already in `test-config.properties` and lets you switch one back on, or add a new group to disable by name (e.g. `perf`, `accessibility`, `visual`) — the same mechanism `test-config.properties`'s own commented-out examples show. A test carrying a disabled group is switched off regardless of its other groups or its own `test.<Class>.enabled` line — see [🎛️ Choosing Which Sites and Tests Run](configuration.md#️-choosing-which-sites-and-tests-run) for the exact precedence rules.

## 🩺 Results Drilldown

Below the summary tiles and the per-class table, two more panels expand on what only showed as a count before:

- **Self-healed locators** — every locator the self-healing engine actually had to fall back on in the last run (original → healed, which site, which stage — DOM/visual/AI), from `target/self-healing/*healing-report.json` — both the bare `healing-report.json` a plain local `mvn test` writes and the `<site>-healing-report.json` files each CI job writes via `-Dself-healing.report.path=...` (a local run shows up under the site label "(local run)"). A locator showing up here repeatedly is worth fixing at the source rather than leaving to self-heal every run.
- **Test stability** — tests ranked worst-first by how unstable they are, each tagged **flaky** (status flipped two or more times), **new failure**, **always failing** (failed in every one of at least three recent runs) or **recovered**, with a strip of ticks for the recent runs, "failed 5 of 6 runs · 2 flips", and where the history came from. Two sources are merged, CI winning when both know a test: `target/flaky-tests.json` (from `compute_flaky_trend.py` against the `gh-pages` history — usually absent on a purely local checkout) and the failing-test names this Console records for every run it sees (`results-history.json`, last 40 failures per run). Local numbers treat "not in that run's failure list" as not failing — the Console cannot tell *passed* from *not part of that run* — so read them as a guide, not an audit. Runs recorded before this feature carry no names and are left out of the counts.

- **Reports** (Overview) — each report folder with whether it exists and when it was last updated, a **Copy how to open** button (for example `allure open target/allure-report`), and, when it can be worked out, an **Open published reports ↗** link. The address is `DASHBOARD_REPORTS_URL` if set (plain `http(s)` only; anything else is ignored), otherwise the conventional GitHub Pages address guessed from a `github.com` `origin` remote (it works only if Pages is enabled; the panel says it is a guess). **The Console deliberately does not serve report pages itself**: a report embeds content the tests visited, and serving it from the Console's own origin would place that untrusted markup beside the authenticated session — and the Console's CSP would break Allure anyway.
- **Failure triage tools** (Failures panel footer) — *Expand all / Collapse all*, and **Copy re-run command**, which builds `mvn test -Dtest='pkg.Class#methodA+methodB,…' -Dsurefire.failIfNoSpecifiedTests=false` for exactly the failed tests (parameterised names such as `login[2]` widen to the whole class). If your suites are driven only by `suiteXmlFiles`, check that your surefire configuration honours `-Dtest`. Press `?` anywhere for the keyboard-shortcut list.
- **Failures by root cause** (Results tab, shown when two or more tests fail) — failing tests grouped by exception type plus the first line of the error, with numbers, ids, hex values and quoted strings masked, so one broken thing is one row (`×7`) instead of seven. Every test is still listed inside its group.

The summary tiles also add a **TIA selected** count (`impacted / total` test classes) when `target/tia/impact-summary.json` exists from a local Test Impact Analysis run.

## 🕓 Audit Log & Undo

Every change made *through the dashboard* — a site toggle, a test toggle, a bulk section action, a preset, a group change, an edit to `run.only` — is appended to `target/dashboard-audit.jsonl` (already gitignored, since `target/` is build output) and shown newest-first in the **Recent changes** panel: when, who (the client IP — there's no login system beyond the shared password, so that's the most this tool can identify), and what changed. It only records edits made through this dashboard's own API; a change made by hand in a text editor, or via `-Dtests.disabled=...` on the command line, leaves no trace here.

**Undo last change** reverts the single most recent entry back to its recorded previous value. Only single-value changes (one site, one test, one group, `run.only`) can be undone this way — a bulk-section or preset change touched several keys at once with no single "previous value" to restore to, so Undo declines those with a clear error rather than guessing.

## 📄 Raw File Viewer & CSV Export

- **View raw file** (under the Sites and Tests panels) opens the exact current text of `pipeline-config.properties` / `test-config.properties` in a read-only modal — useful to sanity-check that a toggle landed where you expected, or just to read the file's own header comments without leaving the browser.
- **Export CSV** (under the results table) downloads the current per-class pass/fail/skip breakdown as a `.csv` — generated client-side from data already on screen, no extra request.

---

## 📋 Copy Run Command

Under each site with matching suite files, a small dropdown lists every `testng-suites/*.xml` file that actually belongs to that site, plus a **Copy run command** button that copies e.g. `mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-smoke.xml` — the exact matched pair `pom.xml`'s own defaults require (see [🎛️ Choosing Which Sites and Tests Run](configuration.md#️-choosing-which-sites-and-tests-run); passing `-Dsite` alone runs the *previous* `-DsuiteXmlFile` default against the new site, which is the mix-up this feature exists to prevent).

The mapping isn't a hardcoded filename-prefix table — it's built by scanning each suite file's actual `com.automation(.mobile)?.sites.<name>` package references (`Scripts/dashboard/suites.py`), so it can't drift out of sync with `testng-suites/` the way a hardcoded table could. A suite file referencing more than one site, or none at all, is left out of every dropdown rather than guessed at.

Copying uses the Clipboard API where available (works on `localhost`) and falls back to the older `execCommand` method elsewhere (a plain `http://` LAN or ngrok URL on another host blocks the Clipboard API as a browser security restriction) — either way, if the copy silently fails, the command still appears in the toast notification to copy by hand.

## 💾 Snapshot Export/Import

**Export snapshot** (under the Sites panel) downloads the *entire* current state — every site, every test, every group, `run.only` — as one `dashboard-snapshot.json` file. **Import snapshot** applies one back: pick a file, and every site/test/group/run-only value in it is written in at most two file writes (one per properties file).

This is the shareable counterpart to the built-in presets: a preset is a maintainer-curated combination checked into `presets.json`; a snapshot is *your* current config at *this* moment, meant to be handed to a specific person for a specific reason — "here's exactly what I had enabled when this bug reproduced," attached to a PR description or sent in Slack.

Every entry in an imported snapshot is validated before anything is written — the same character allow-list the individual setters use — and a corrupted or hand-edited file never applies partway: unrecognized entries are dropped and listed back to you (`Scripts/dashboard/snapshot.py`'s `validate()`), the rest still applies. An unrecognized schema version is treated as a warning, not a hard failure.

---

## 🧭 Test Hub — start here (`/hub`)

One page for people who just want to test something and read a clear result.

1. **Pick what to test** — website click-through, site bug scan, accessibility, load/speed, API (hand-written), API from a Swagger/OpenAPI link, mobile app, a ready-made suite, framework health, reports. Every card lists **You'll need** (required) and **Optional**.
2. **Answer only what is needed.** Test Wizard and Control Center forms show the required fields first; everything else sits under a collapsed **Optional — helps the test** section, and *Next* / *Run* unlock as soon as the required ones are valid. The server re-checks every rule — the browser is a convenience, never the gate.
3. **Check connection** (next to every address field) makes one request, follows no redirects, returns only status and timing, and refuses link-local / cloud-metadata addresses. It catches a typo or a stopped app before a long run fails on it.
4. **Read the result.** Every finished run gets a verdict (Passed / Failed / **No results** / Cancelled / Timed out / Error), pass-fail counts, the failing checks with their messages, plain-language **What to try next** hints (unknown host, connection refused, certificate problem, browser could not start, element not found, Appium unreachable, 401/403, rate limiting, out of memory, Maven problems…), and links to the reports, printable summary and full log. Counts come only from test-result files written *during that run*, so a run that produced none is never credited with an earlier run's results.
5. **Is this computer ready?** — Maven, Java, Docker, Appium, Ollama and the secret store, each with a one-line fix.

New test types in the wizard: **API from OpenAPI / Swagger** (credentials come from saved secrets and reach the run as `API_TOKEN` / `API_USER` / `API_PASSWORD` / `API_KEY`, never on the command line) and **Mobile app check** (Appium-driven crawler; the built-in "never tap" safety words can be extended but not shortened). The API type gained optional basic / API-key login, extra headers, retries and self-signed certificates.

Deep links: `/wizard?type=openapi`, `/control?feature=bug-crawler`, `/control?group=Reports`, `/hub#run=<run id>`.

## 🧙 Test Wizard

`/wizard` is a six-step page for people who don't write Java: **1 Test type → 2 Target → 3 Details → 4 Reports & evidence → 5 Review → 6 Run.** Types: UI (browser steps), API (REST requests), performance, site crawler, accessibility. Every field is validated live (URL, XPath/CSS locators, required fields, numeric limits) **and again on the server** when you save or run — the browser check is a convenience, never the gate.

- **Templates** — save a setup on the Review step; each save is a new version (`data/templates/<name>/vNNNN.json`); load, re-run, or (lead+) delete from step 1.
- **UI scenarios** become a keyword script + object repository + suite XML in the run's own folder and are replayed by `WizardKeywordRunTest` through the normal keyword engine. API specs become an `api-test.sh` spec; performance and accessibility pick the site's existing suite and set `perf.*` / `a11y.*`; the crawler sets `crawler.*`.
- **Secrets never go in a spec.** Password/token fields take a reference — `${secret:NAME}` (encrypted store, admin-managed) or `${env:NAME}` (from the dashboard's environment). The save/run validators reject anything that looks like a literal credential. A secret store needs `pip install cryptography` and `DASHBOARD_SECRET_KEY` (a Fernet key); without them it stays disabled (fails closed) and `${env:…}` still works.
- **Running is OFF by default.** Set `DASHBOARD_RUNNER_ENABLED=1` to allow *Run now*. The runner can execute only the commands in `runner/allowlist.py` — fixed programs, `-Dkey=value` arguments whose key is on a per-type list and whose value matches a strict pattern, no shell. Runs are queued (FIFO, **one at a time** by default — they share the repo's `target/`; `DASHBOARD_MAX_PARALLEL` raises it), time-limited, cancellable, get a clean environment (only `PATH`, `HOME`, … plus the secrets/env vars the spec names), and keep the newest 30 runs / 14 days under `data/runs/<id>/` (`spec.json`, `properties.generated`, `status.json`, `run.log`, `summary.html`, report zips).
- **CI trigger** (lead+) starts the repo's own GitHub Actions / GitLab / Jenkins pipeline with suite type, browser and headless. Configure with `DASHBOARD_CI_*` environment variables (see `runner/ci_trigger.py`). It can't ship a wizard-built scenario to CI — that only exists on this machine.
- **Accounts & roles** — create `tester` / `lead` / `admin` users with `python3 Scripts/dashboard/auth/users.py add NAME ROLE`. Once `auth/users.json` exists, per-user logins are required (plus the old shared `dashboard` login, which counts as admin) and the existing site/test toggles need `lead`. Testers can build, save and run; leads can also delete templates, cancel anyone's run and trigger CI; admins manage secrets.
- **Audit** — spec saves, run starts/cancels, CI triggers and secret changes (names only, never values) are logged to `target/dashboard-wizard-audit.jsonl`, separate from the config log so *Undo last change* is unaffected.
- **Viewing results** — the run page shows live status and log; each run has a `summary.html` and a zip per report folder. Allure/Extent reports are offered as zips because the console's strict CSP (no inline scripts) would break them if served inline.

### Results view (Console)

The "Last run — by class" panel shows a pass-rate donut, a pass-rate trend across the last 30 runs (kept on the server in `<data dir>/results-history.json`, so every browser sees the same trend and clearing site data does not reset it; one entry per run, stamped with the run's own report time, capped at 100 runs; the file is git-ignored with the rest of the data dir), per-class pass/fail/skip bars, a status filter (All / Failing / Passing), class search, sortable columns, and an auto-refresh switch that pauses while the tab is hidden. Keyboard: `/` focuses the search box, `r` reloads everything. "Export CSV" exports exactly the rows currently shown (quoted per RFC 4180, with spreadsheet-formula guarding).

### Input and method rules worth knowing

- The Console only flips switches that already exist. A site or test name that is not already in `pipeline-config.properties` / `test-config.properties` is refused with `400 unknown site|test`, so a typo can never create a phantom site that the pipelines then try to run. Group switches are the exception: "Add off" may create a new group. Names are capped (200 characters; groups 64).
- `HEAD /api/health` answers 200 for liveness probes; other methods (PUT, DELETE, PATCH, OPTIONS) get `405` with an `Allow` header.
- The listen backlog is 128, so bursts of parallel requests are queued rather than reset.

Tests: `python3 -m unittest discover -s Scripts/dashboard/tests -t Scripts/dashboard` (also run in CI).

---

## 🕹️ Control Center (every feature, no commands)

`http://localhost:5057/control` lists **every runnable capability of the framework as a card with switches** —
all 30 TestNG suites (UI, API, BDD, mobile, accessibility, visual, performance, Safari), plus API spec runs, unit
tests, Checkstyle, OWASP/SBOM/mutation/license scans, the bug crawlers, the recorder, Allure / segmented / PDF /
e-mail reports, Test Impact Analysis, coverage map, artifact pruning, the project audit and (opt-in) Docker grid and
site scaffolding.

Start it with **one command** (it turns the runner on for you):

```bash
Scripts/start-dashboard.sh                    # then open http://localhost:5057/control
DASHBOARD_RUNNER_ALLOW_SYSTEM=1 Scripts/start-dashboard.sh   # also enables Docker-grid + scaffolding buttons
```

* **Run switches** (top of the page) — browser, headless, retries, video, AI failure analysis, PDF report,
  quarantine, performance budgets, self-healing, CAPTCHA auto-detect, Selenium Grid. They apply to every test card
  that supports them; only switches you actually change are sent, everything else keeps the framework default.
* **Cards** — each has its own *Switches* drawer (site, tags, load profile, crawl depth, dry-run …) and a **Run** button.
  Cards that can't run here say why (no Docker, Appium not reachable, macOS-only Safari …).
* **Bundles** — queue several features back-to-back (quick demo, UI showcase, quality gate).
* **Runs panel** — live log, Cancel, Re-run, run summary and zipped reports for the selected run.

Safety model: each card maps to one fixed program (`mvn`, `bash Scripts/…`, `python3 …`, `docker compose …`). The browser can
only send switch *values*, and each is validated by type (bool / enum / whole number / http(s) URL / git ref / name) before it
becomes a `-Dkey=value` or flag. No free-form command or argument can be passed; runs use `shell=False`.
Risky actions are safe by default (e-mail and artifact pruning are **dry-run** unless you switch that off) and
system-level actions need `DASHBOARD_RUNNER_ALLOW_SYSTEM=1`. Catalog: `Scripts/dashboard/features/catalog.py`
(add a card by adding one entry); tests: `Scripts/dashboard/tests/test_features.py`.

## 🧭 Console Layout, Overview & Command Palette

The main page (`/`) is split into four tabs, addressable by URL hash so a link such as `/#results` opens straight to that tab:

| Tab | What's on it |
|---|---|
| **Overview** | KPI tiles (tests, **pass rate with the change since the previous run**, passed, failures, skipped, **run time**, coverage, self-healed, flaky, TIA), a **pass-rate trend chart** (one bar per recorded run, hover for the date and counts), **Needs attention** (failing tests with the first line of each error), **Slowest tests**, an **Environment** card (repo, branch, commit, last run, signed-in user and role, whether the runner is on, which report folders exist) a **Next run plan** card (sites on/off, test classes switched off, groups switched off and `run.only`, read live from the two config files, with a warning when no site is enabled) and quick links to the other tools |
| **Configuration** | Sites, presets, *Run only*, groups and the per-test switches (unchanged behaviour) |
| **Results** | **Failures** (expand a case for its full error message), the by-class table (now with a **Time** column) with search, filter and sort, plus self-healed locators and flaky tests. A red badge on the tab shows the failure count |
| **Activity** | The audit log and *Undo last change* |

**Command palette** — press `Ctrl K` (`⌘ K` on macOS) anywhere: jump to a tab or tool, refresh, toggle the theme, export CSV or a snapshot, view either properties file, undo, or **enable/disable any site by typing its name**. `↑ ↓` to move, `Enter` to run, `Esc` to close. `/` focuses the results search and `r` refreshes. **Copy run summary** (Overview quick action, Results footer or palette) puts a Markdown summary — counts, pass rate, run time, coverage, and the failures grouped by root cause — on the clipboard, ready for a PR comment, ticket or chat.

**Failure details and timings** come from `target/surefire-reports/TEST-*.xml` (the same per-class files the totals use; the aggregate `TEST-TestSuite.xml` is skipped so nothing is double counted). Error text is length-capped and shown as plain text.

**Connection monitor** — if the server stops answering, a red banner appears and the status pill reads *offline*; the Console keeps the last data on screen, polls every 5 s and reloads itself when the server is back.

**Theme** — follows the operating system until you click the toggle, then remembers your choice. The choice is applied by one shared `static/theme.js` loaded in `<head>` on **every** page (Console, Test Hub, Control Center, Test Wizard), so there is no dark-to-light flash and the pages never disagree; each page has the toggle and a link back to the Console. Both themes meet WCAG AA contrast for status colours. The page is keyboard-operable (skip link, focus rings, real buttons for collapsible sections, dialogs that trap and restore focus, and switches that keep keyboard focus after a toggle) and has a print stylesheet.

### Run history tools

Every run the Console sees is recorded (server-side, `results-history.json`) with its pass rate, **total run time**, the **slowest tests** with their seconds, and the **first error line of each failing test**. Runs recorded before this was added carry only the pass rate and are filled in once if they are still the current run.

- **Run-time trend** (Overview) — bars of total test time per run with a dashed median line; a run more than 25 % over the median is amber.
- **Compare two runs** (Results tab) — pick a baseline and a second run: *newly failing* (with the error and whether it was passing or not in the baseline), *fixed*, *still failing*, *was failing, now skipped or not run*, tests that got *slower* (at least 2 s and 1.5×) or *faster*, and the change in total time. Served by `GET /api/results/compare?a=<t>&b=<t>`. **Exact vs approximate:** since this was added, every run the Console sees also stores *every test's* outcome (passed / failed / skipped) and time in `<data dir>/results-cases.json` (newest 40 runs, 3000 tests per run, mode 0600, re-validated on read). Two runs that both have that are compared test by test — "fixed" means *now passing*, a test that was failing and is now skipped or absent is reported separately, and times are compared for every test. A run recorded before that has only its failing tests and slowest tests (the rest was never kept and cannot be recovered), so a comparison involving it falls back to the approximate rule and the panel says so.
- **Test history drawer** — the **History** button on a Test stability row opens the test's recorded runs (date, failed or not, time, error), its status, flips, average/slowest time and its last error. For exactly-recorded runs each row says passed / failed / skipped / not in this run with the real time; older runs only know failures and (for the slowest tests) a time, and the drawer says how many of each it used. `GET /api/results/test?name=…`.
- **Quarantine** — in that drawer, switches off the test's **whole class** in `test-config.properties` (test-level switches do not exist in the configuration), after a confirmation. It is the same write as the Configuration tab: it needs the same permission, is recorded in Activity, and can be undone there.
- **Threshold alerts** — Preferences → *Alerts*: a banner when the latest pass rate or core coverage is below your line, or the run took more than N % longer than the median of the previous runs (needs at least four timed runs). Off by default, per login. Dismissing hides it until the run or the wording changes. No messages are sent anywhere; Slack/Teams notification remains a CI-side concern.
- **Saved views** (Results tab) — the search, status filter and sort live in the address bar (`?rq=…&rs=…&rk=…&rd=…`), so a view can be bookmarked or shared (**Copy link**); **Save current…** keeps named views (up to 12) in this browser. Everything read from the URL or storage is checked against fixed choices before use.

### Live run view

When the test runner is enabled (`DASHBOARD_RUNNER_ENABLED=1`) and your login may view runs, a **Run in progress** panel appears on Overview while a run is queued or running: classes finished, tests, failing tests (read from Maven's own `Tests run: … in <Class>` lines), the last log lines (colour codes stripped) and a **Stop run** button that uses the same cancel endpoint and permission rule as the Test Hub. It polls every 3 s while a run is active and every 15 s otherwise (every 30–60 s when the runner is off or unavailable to the login), and refreshes the results when a run finishes.

### Environment health

**Check now** (Overview, or the palette) makes one short request (a TCP connect for the emulator) to each service the framework depends on — Selenium Grid (`grid.url`), Ollama (`ai.endpoint`), ReportPortal (`rp.endpoint`) Appium (`appium.server.url`) and the **Android emulator/device**. The emulator is checked **without running `adb`**: `mobile.device.name` is read and, when it is `emulator-<port>` (the emulator's console port on this machine) or `host:port` (Genymotion / Wi-Fi device), a plain TCP connection is opened and closed at once — nothing is sent. A plain name such as "Samsung Galaxy S24" cannot be checked that way, and the panel says so — and shows reachable / not reachable, the HTTP status and the latency. It never runs by itself, the targets come only from your config files (never from the request), only the host and port of each configured URL are used (credentials, paths and query strings are dropped and never shown), a fixed health path is requested, redirects are not followed, the body is not read, and link-local, multicast and unspecified addresses are refused. Results are cached for 5 s. A Grid that is unreachable while `grid.enabled=false` is shown as expected, not as a failure. `GET /api/health/deps`.

### Preferences, density and the phone layout

Open **Preferences** (⚙ in the header, or the command palette) to set, per login: **theme** (match system / light / dark), **density** (comfortable / compact), the **tab to open on**, and the **auto-refresh interval** (off, 15 s, 30 s, 1 min, 5 min). They are stored server-side in `<data dir>/preferences.json` (mode 0600) keyed by user name, so the same person gets the same Console on every browser; changes apply immediately. `GET/POST /api/prefs` serves them; every value must be one of a fixed list of choices (anything else is a 400), a hand-edited file can never feed the UI an unlisted value, and at most 200 users are stored. Any signed-in role — including one that cannot edit the configuration — may change **its own** preferences; the route sits in front of the `config.edit` gate on purpose and does not open that gate. A browser's existing local theme/density choice is only replaced by a value you actually saved on the server, never by a default. Compact density tightens padding and type for more rows on screen. On a phone the section tabs move to a fixed bar at the bottom, wide tables scroll sideways inside their panel, and touch targets grow to about 44 px.

### HTML run report

**Export HTML report** (Overview quick action, Results footer, or palette) downloads one self-contained `.html` file — verdict, KPIs, failures grouped by root cause with the first error, test stability, slowest tests and a per-class table. It is built in the browser from the data the Results tab already has; everything that came from test output is inserted as escaped text, never as markup. For a PDF, open the file and use the browser's **Print → Save as PDF** (the page carries print styles). The Console does not generate PDFs server-side.

`GET /api/system` (authenticated) feeds the Environment card. It reads the git branch and commit straight from `.git`, **without running git**, so the "no subprocess in the default configuration" guarantee still holds.

### Request validation (strict by design)

The config-editing endpoints require real JSON types: `enabled` must be `true`/`false` (not `"false"`, not omitted), `name` and `value` must be strings, and `/api/test-config/run-only` needs an explicit `"value"` (send `""` to clear). Anything else is `400`, never a silent change. Request bodies must use `Content-Length` (chunked bodies get `411`). Responses carry CSP (`object-src 'none'`, no inline script or style), COOP/CORP `same-origin` and a restrictive Permissions-Policy, and static files are served with `Cache-Control: no-cache`. Starting with `--host` other than loopback prints a warning, because Basic auth over plain HTTP is unencrypted — put it behind HTTPS or an SSH tunnel.

## 🚫 What It Deliberately Doesn't Do

**By default there is no "Run tests" button.** The original dashboard only reads `target/` and edits the two properties files — it never shells out to Maven, git, or anything else, and it never executes anything the browser sends it (see `dashboard_server.py`'s module docstring). Running `mvn test` from a web request is a materially different security posture than editing two config files. The [🧙 Test Wizard](#-test-wizard) can change that, but only if you set `DASHBOARD_RUNNER_ENABLED=1` yourself, and then only through the fixed allowlist described above. Leave it unset and nothing here can run a process; kick off runs yourself (`mvn test ...`, a CI pipeline, Jenkins) and hit **Refresh**. Whatever you choose, keep the dashboard on loopback or behind a tunnel with a strong password — see [🔐 Authentication](#-authentication).

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
| Browser keeps re-prompting for a password | Wrong password, or that IP is locked out (10 failed attempts/60s) — wait a minute and retry |
| "No test results yet" | No `target/surefire-reports` — run `mvn test` (against any site) once, then **Refresh** |
| Coverage tile missing | No `target/coverage-summary.json` and no `target/site/jacoco/jacoco.xml` — run `mvn verify -Punit-tests` (or the CI coverage-gate job) at least once |
| A toggle doesn't seem to take effect | Confirm the dashboard's repo root (`/api/health`) matches the checkout your `mvn test` runs from — under Docker this is whatever `docker-compose.dashboard.yml`'s bind mount points at |
| "Maven not found … no Java or Maven" when starting a run from the Dockerised dashboard | The standalone dashboard image has neither | Run tests on the host or via `docker compose run --rm tests`; see [DOCKER.md](../DOCKER.md#️-dashboard-image-can-it-run-tests) |
| `docker compose --profile dashboard up` starts nothing | The `dashboard` profile is opt-in on purpose — `--profile dashboard` must be passed explicitly, plain `docker compose up` skips it |

## Quality gate

The Overview tab opens with a **Quality gate**: a READY / BLOCKED / NO DATA verdict for the latest run, with one row per check (pass rate, failing tests, core coverage, run time against the recent median, flaky tests). A check with nothing to measure shows `?` and never counts as a pass.

Limits come from your Preferences (the alert thresholds) and, for coverage, the build's own `jacoco.core.minLineCoverage`. CI can call the same verdict:

```bash
curl -fsS -u dashboard:"$DASHBOARD_PASSWORD" "http://localhost:5057/api/quality-gate?strict=1"
```

`strict=1` turns a failing gate into HTTP 422 so `curl -f` fails the step. Override any limit per call with `min_pass`, `max_failed`, `min_coverage`, `max_slowdown`, `max_flaky`; a bad value is a 400.
