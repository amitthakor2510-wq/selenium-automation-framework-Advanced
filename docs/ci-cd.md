# CI/CD Pipelines

All three pipelines cover the same three test tracks per commit — browser
sites (demoqa, saucedemo), mobile (Android emulator + Appium), and a nightly
demoqa-only accessibility/visual suite — plus Allure + Extent reporting.

## 🔄 Jenkins CI/CD Setup

### One-time setup
1. `Manage Jenkins → Tools` → Add JDK named exactly `JDK17`
2. `Manage Jenkins → Tools` → Add Maven named exactly `Maven3`
3. `Manage Jenkins → Tools` → Add Allure Commandline named exactly `allure`
4. Install **HTML Publisher** plugin
5. Create Pipeline job → SCM: Git → Script Path: `Jenkinsfile`
6. The `Mobile Test` stage needs an Android-capable agent: Node.js (for
   Appium), `wget`/`unzip`, and ideally `/dev/kvm` access for a fast
   emulator boot. If your default agent doesn't have these, point this
   job at one that does (e.g. via an agent label).

### Build parameters
| Parameter | Options | Default | Purpose |
|---|---|---|---|
| `SUITE_TYPE` | regression / smoke | regression | Which suite type to run for each discovered site |
| `SITE` | ALL / site name / `mobile` | ALL | Run all sites, one browser site, or just `mobile` |
| `BROWSER` | chrome / firefox / edge | chrome | Browser to use (browser sites only — ignored by mobile) |
| `HEADLESS` | true / false | true | Show browser or not (browser sites only) |
| `RETRY_COUNT` | integer | 0 | Retries for failed tests — 0 disables for CI speed |

### Pipeline stages
```
Cleanup (Stale Processes) → kills any orphaned node/adb/qemu-system-x86_64 processes and
                           stale AVD/adb *.lock files left behind by a crashed previous
                           run, before checkout — best-effort, no-ops on a clean agent
Checkout                → git checkout
Build                    → mvn clean compile test-compile (fails fast on compile errors)
Discover Site Projects   → globs testng-suites/*-<SUITE_TYPE>.xml to find browser sites;
                           "mobile" is excluded here and handled by its own stage below
Run Tests Per Site       → one parallel branch per discovered browser site
Mobile Test              → only runs when a mobile-<SUITE_TYPE>.xml suite exists (or
                           SITE=mobile/ALL): installs the Android SDK, creates/boots an
                           AVD, installs Appium + the uiautomator2 driver, then runs the
                           mobile suite against it. ANDROID_ADB_SERVER_TIMEOUT=120 is set
                           for this stage to tolerate a slower local emulator boot under
                           resource contention (Jenkins + GitLab + Appium sharing one box)
Nightly Extra Coverage   → demoqa accessibility + visual suites, only on the cron trigger
```

Build history is capped to the last 10 builds (`buildDiscarder`). The
`post { always { ... } }` block also runs `adb reconnect offline` (only
when the Mobile Test stage ran) and `cleanWs()` after archiving
artifacts, to keep adb's connection state and workspace disk usage from
creeping up across many local runs.

### After build — where to look
```
Job → Build #N
├── Console Output      → full Maven logs, errors, test output
├── Test Results        → pass/fail count, failed test names
├── Extent Test Report  → custom HTML report tab
└── Artifacts           → download screenshots, Allure results, and HTML report
```

### Fix report styling in Jenkins
Jenkins blocks external CSS by default — report appears unstyled.

Quick fix (resets on restart) — run in Script Console:
```groovy
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
```

Permanent fix — systemd override:
```bash
sudo systemctl edit jenkins
# Add these lines:
[Service]
Environment="JAVA_OPTS=-Dhudson.model.DirectoryBrowserSupport.CSP="
# Save, then:
sudo systemctl daemon-reload
sudo systemctl restart jenkins
```

---

## 🐙 GitHub Actions Pipeline

`.github/workflows/github-ci.yml` runs on every push/PR to `main`/`master`,
plus a manual `workflow_dispatch` with its own inputs, plus a nightly cron —
and is the only one of the three pipelines that also **publishes a live
Allure report with trend history** to GitHub Pages.

```
build                      → mvn clean compile test-compile (fails fast on compile errors)
test                        → matrix job, one instance per browser site (demoqa, saucedemo):
                              installs Chrome if missing, runs the suite headless, uploads
                              Allure results + Extent/screenshots/surefire as artifacts
mobile-test                 → separate job: enables KVM, installs Appium + uiautomator2,
                              starts Appium, boots an Android emulator via
                              reactivecircus/android-emulator-runner, and runs the mobile
                              suite against it (mvn command must stay on a single line —
                              this action does not execute a `\`-continued multi-line
                              script as one shell command)
accessibility-visual-test   → nightly only (gated on the schedule trigger): demoqa
                              accessibility + visual suites
allure-report                → downloads this run's Allure results (all sites + mobile) +
                              previous run's history from the gh-pages branch, merges them
                              for trend graphs, deploys both the Allure report and the
                              Extent report to GitHub Pages under /allure-report and
                              /extent-report
```

### Manual dispatch inputs
| Input | Options | Default |
|---|---|---|
| `suite_type` | regression / smoke | regression |
| `browser` | chrome / firefox / edge | chrome |
| `headless` | boolean | true |
| `retry_count` | string | 0 |

A push to the same branch/PR cancels whatever run is still in progress for
that ref (`concurrency:` block).

The `allure-report` job needs `contents: write` permission and a `gh-pages`
branch to accumulate history in — first run will simply start fresh history
if that branch doesn't exist yet.

---

## 🦊 GitLab CI/CD Pipeline

### Pipeline stages
```
build                      → mvn compile — catches syntax errors before wasting time on tests
test                        → parallel:matrix job, one instance per browser site
                              (demoqa, saucedemo) — installs Chrome if missing on runner
mobile-test                 → separate job, serialized via resource_group (this runner is a
                              persistent shared shell host, not an isolated container —
                              without serializing, two overlapping runs race on the same
                              AVD name/Appium port and can kill each other's live Appium
                              server). Installs the Android SDK, boots an emulator,
                              installs Appium + uiautomator2 (checked independently of
                              whether the appium binary itself is already present — this
                              host persists between runs), then runs the mobile suite
accessibility-visual-test   → scheduled pipelines only (`$CI_PIPELINE_SOURCE == "schedule"`):
                              demoqa accessibility + visual suites — create the schedule
                              under Settings > CI/CD > Schedules
report                       → merges every job's Allure results into one report, publishes
                              it + the Extent report under public/, prints a JUnit summary
pages                        → publishes public/ to GitLab Pages
```

### Variables you can override per run
```
SUITE_TYPE  = regression (or smoke)
BROWSER     = chrome     (or firefox, edge — browser sites only)
HEADLESS    = true       (always true on CI)
RETRY_COUNT = 0
```

A superseded pipeline (e.g. a second push to the same MR before the first
pipeline finishes) auto-cancels the older one (`workflow.auto_cancel`).

### View report after pipeline
```
GitLab Pages URL → /allure-report      (full merged Allure report, all sites + mobile)
GitLab Pages URL → /extent-report      (Extent HTML report)
GitLab Job → Browse Artifacts → target/extent-reports/, target/allure-results/
```

---

## 🖥️ Running Jenkins + a self-hosted GitLab + Appium/emulator on one local machine

If Jenkins and GitLab are both self-hosted on the same box that also runs
the Appium/emulator stack for mobile tests (rather than each on separate
infrastructure), a Jenkins build triggered by a GitHub push can starve
GitLab's own background processes of CPU/IO while it compiles and boots
the emulator at the same time — this showed up as GitLab's pipeline UI
throwing "unable to fetch pipeline jobs/data" errors specifically during
overlapping Jenkins+emulator load, not as an actual pipeline config bug.
Confirm this is what's happening by watching `sudo gitlab-ctl tail puma`
and `sudo gitlab-ctl tail sidekiq` during a push.

Fixes applied for this setup:
- **`/etc/gitlab/gitlab.rb`**: capped `sidekiq['concurrency']` to 5,
  `puma['worker_processes']` to 2, trimmed PostgreSQL/Redis memory
  ceilings, and disabled the bundled Prometheus monitoring stack
  (`prometheus_monitoring['enable'] = false`) — all overkill for a
  single-user local instance. Apply with `sudo gitlab-ctl reconfigure &&
  sudo gitlab-ctl restart` after editing. Only use setting names that
  appear (even commented-out) in your own `gitlab.rb` — an unrecognized
  key like a stale `grafana[...]` line makes `reconfigure` fail outright
  with `Mixlib::Config::UnknownConfigOptionError` rather than being
  silently ignored.
- **`Jenkinsfile`**: added a `Cleanup (Stale Processes)` stage that runs
  first, before checkout, killing any orphaned `node`/`adb`/
  `qemu-system-x86_64` processes and stale AVD/adb lock files left by a
  previous crashed run — see the Pipeline stages diagram above.

---
