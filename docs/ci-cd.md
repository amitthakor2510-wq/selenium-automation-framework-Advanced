# CI/CD Pipelines

All three pipelines cover the same three test tracks per commit — browser
sites (demoqa, saucedemo), mobile (Android emulator + Appium), and a nightly
demoqa-only accessibility/visual suite — plus Allure + Extent reporting,
a nightly OWASP dependency-vulnerability scan, and secret scanning
(gitleaks). Safari only runs in the GitHub Actions pipeline — see
[🧭 Safari](#-safari).

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
| `BROWSER` | chrome / firefox / edge | chrome | Browser to use when `ALL_BROWSERS` is unchecked |
| `ALL_BROWSERS` | boolean | false | Run every discovered site against chrome, firefox, **and** edge in parallel — ignores `BROWSER` above. Off by default so a normal build's branch count/runtime is unchanged. |
| `HEADLESS` | true / false | true | Show browser or not (browser sites only) |
| `RETRY_COUNT` | integer | 0 | Retries for failed tests — 0 disables for CI speed |
| `SECURITY_FAIL_CVSS` | number | 11 | OWASP Dependency-Check: fail the nightly Security Scan stage on any dependency with a CVSS score at or above this value. 11 = never fails (report-only); 7 is a common "fail on High/Critical" cutoff |

### Pipeline stages
```
Cleanup (Stale Processes) → kills any orphaned node/adb/qemu-system-x86_64 processes and
                           stale AVD/adb *.lock files left behind by a crashed previous
                           run, before checkout — best-effort, no-ops on a clean agent
Checkout                → git checkout
Build                    → mvn clean compile test-compile (fails fast on compile errors)
Checkstyle               → mvn checkstyle:check@checkstyle-check — style violations mark
                           the build UNSTABLE, not a hard failure
Secret Scan              → gitleaks against the working tree — findings mark the build
                           UNSTABLE (report-only until an initial pass is triaged), runs
                           on every build since it's fast (no CVE database to build)
Discover Site Projects   → globs testng-suites/*-<SUITE_TYPE>.xml to find browser sites;
                           "mobile" is excluded here (handled by its own stage below), and
                           any *-safari-<SUITE_TYPE>.xml files are filtered out too — kept
                           in the repo for the GitHub Actions pipeline's Safari job, but
                           Jenkins itself no longer runs Safari at all (see 🧭 Safari)
Run Tests Per Site       → one parallel branch per site (x per browser if ALL_BROWSERS is
                           checked) — each branch writes its own
                           target/jacoco-artifacts/<key>.exec via -Djacoco.destFile so
                           parallel branches sharing one workspace don't collide on the
                           default target/jacoco.exec path
Mobile Test              → only runs when a mobile-<SUITE_TYPE>.xml suite exists (or
                           SITE=mobile/ALL): installs the Android SDK, creates/boots an
                           AVD, installs Appium + the uiautomator2 driver, then runs the
                           mobile suite against it. ANDROID_ADB_SERVER_TIMEOUT=120 is set
                           for this stage to tolerate a slower local emulator boot under
                           resource contention (Jenkins + GitLab + Appium sharing one box)
Coverage Gate             → merges every branch's jacoco-artifacts/*.exec (jacoco:merge),
                           then jacoco:check enforces 50% line coverage on
                           com.automation.core.* against the merged union — UNSTABLE on
                           breach, not a hard failure. Report published via HTML Publisher.
Nightly Extra Coverage   → demoqa accessibility + visual suites, only on the cron trigger
Performance Smoke        → perf/basic-smoke.jmx via the pom.xml `perf` profile, only on
(Nightly)                  the cron trigger — response-time smoke check, UNSTABLE (not a
                           hard failure) on breach since it hits sites this repo doesn't
                           control
Security Scan (Nightly)  → mvn verify -Psecurity (OWASP Dependency-Check) against the NVD
                           CVE database, only on the cron trigger since the first-ever run
                           downloads/builds that database and is slow. Report-only by
                           default (SECURITY_FAIL_CVSS=11) — UNSTABLE, not a hard failure,
                           unless that build parameter is lowered
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

## 🧭 Safari

Safari only runs in the GitHub Actions pipeline. It needs a real macOS
machine (see [🧭 Safari](configuration.md#-safari) in the Configuration
guide for why) and runs `parallel="none"` (one session per machine), so
Jenkins and GitLab CI — both running on Linux shell-executor hosts here,
with no macOS agent/runner registered — don't run it at all; wiring it
into either would just queue forever with nowhere to run. The dedicated
`testng-suites/<site>-safari-<suite>.xml` suite files stay in the repo
for GitHub Actions' use; Jenkins' site-discovery stage explicitly filters
them back out so they don't get mistaken for a real site.

| Pipeline | Safari support |
|---|---|
| GitHub Actions | Runs automatically on every push (`test-safari` job, `macos-latest`), plus on-demand via manual `workflow_dispatch` with `run_safari` checked |
| Jenkins | Not supported — no macOS agent |
| GitLab CI | Not supported — no macOS runner |

GitHub Actions' `test-safari` job enables `safaridriver`, matrixes over
site (demoqa, saucedemo), and passes `-Dbrowser=safari` with **no**
`-Dheadless` flag — Safari has no headless mode, and `DriverFactory`
already warns and continues if one reaches it anyway. Results land
alongside every other browser's in the same merged Allure/Extent report,
under a `<site>-safari` results subdirectory so they never collide with
`<site>`'s own chrome/firefox/edge run.

Safari deliberately doesn't feed the JaCoCo coverage gate — the same
`core/` code paths are already exercised by the chrome/firefox/edge runs,
so including it would only make the coverage gate flakier without
covering any additional code.

---

## 🐙 GitHub Actions Pipeline

`.github/workflows/github-ci.yml` runs on every push/PR to `main`/`master`,
plus a manual `workflow_dispatch` with its own inputs, plus a nightly cron —
and is the only one of the three pipelines that also **publishes a live
Allure report with trend history** to GitHub Pages.

```
build                      → mvn clean compile test-compile (fails fast on compile errors)
checkstyle                  → mvn checkstyle:check@checkstyle-check, parallel with test/
                              mobile-test — a violation fails this job (surfaced on PRs via
                              the pr-comment job below), doesn't block the test matrix
test                        → matrix job: site (demoqa, saucedemo) x browser (chrome,
                              firefox, edge) = 6 parallel instances on every push/PR/
                              schedule run, or just the one browser picked via
                              workflow_dispatch. Installs the matching browser if missing,
                              runs the suite headless, uploads Allure results + Extent/
                              screenshots/surefire + (chrome leg only) jacoco.exec as
                              artifacts
test-safari                  → runs automatically on every push (also runnable on-demand
                              via manual workflow_dispatch with run_safari checked — see
                              🧭 Safari above), macos-latest runner — matrix over site
                              (demoqa, saucedemo), enables safaridriver, runs the dedicated
                              <site>-safari-<suite>.xml suite, uploads results under a
                              <site>-safari results directory
mobile-test                 → separate job: enables KVM, installs Appium + uiautomator2,
                              starts Appium, boots an Android emulator via
                              reactivecircus/android-emulator-runner, and runs the mobile
                              suite against it (mvn command must stay on a single line —
                              this action does not execute a `\`-continued multi-line
                              script as one shell command). Also uploads its own
                              jacoco.exec artifact
coverage-gate                → downloads every jacoco-exec-* artifact from test/mobile-test,
                              merges them (jacoco:merge@jacoco-merge — no single job
                              exercises all of core/ alone), then jacoco:check@jacoco-check
                              enforces 50% line coverage on com.automation.core.* against
                              the union. Merged HTML report published as an artifact
accessibility-visual-test   → nightly only (gated on the schedule trigger): demoqa
                              accessibility + visual suites
security-scan                → nightly only (gated on the schedule trigger): mvn verify
                              -Psecurity (OWASP Dependency-Check) against the NVD CVE
                              database, cached across runs. Report-only by default
                              (SECURITY_FAIL_CVSS=11, overridable via a repo/org Variable)
secret-scan                  → runs on every push/PR: gitleaks against the working tree.
                              continue-on-error: true for now — report-only until an
                              initial pass across history/fixtures is triaged
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
| `run_safari` | boolean | false |

`test-safari` already runs on every push automatically — see
[🧭 Safari](#-safari). The `run_safari` input only matters for a manual
`workflow_dispatch` run (e.g. re-running Safari alone against a specific
branch without pushing).

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
checkstyle                  → mvn checkstyle:check@checkstyle-check, same `test` stage as
                              test/mobile-test/perf-smoke so it runs in parallel with them
test                        → parallel:matrix job, one instance per site x browser
                              (demoqa/saucedemo x chrome/firefox/edge = 6 instances) —
                              installs the browser if missing on the runner. Each instance
                              also copies its jacoco.exec to
                              target/jacoco-artifacts/<site>.exec on the chrome leg only (a
                              bare jacoco.exec would collide across matrix instances on
                              GitLab's artifact merge, and all three browsers exercise the
                              same core/ code anyway)
mobile-test                 → separate job, serialized via resource_group (this runner is a
                              persistent shared shell host, not an isolated container —
                              without serializing, two overlapping runs race on the same
                              AVD name/Appium port and can kill each other's live Appium
                              server). Installs the Android SDK, boots an emulator,
                              installs Appium + uiautomator2 (checked independently of
                              whether the appium binary itself is already present — this
                              host persists between runs), then runs the mobile suite.
                              Copies its jacoco.exec to target/jacoco-artifacts/mobile.exec
accessibility-visual-test   → scheduled pipelines only (`$CI_PIPELINE_SOURCE == "schedule"`):
                              demoqa accessibility + visual suites — create the schedule
                              under Settings > CI/CD > Schedules
security-scan                → scheduled pipelines only: mvn verify -Psecurity (OWASP
                              Dependency-Check) against the NVD CVE database. Report-only
                              by default (SECURITY_FAIL_CVSS=11, overridable as a CI/CD
                              variable)
secret-scan                  → runs on every push/MR: gitleaks against the working tree.
                              allow_failure: true for now — report-only until an initial
                              pass across history/fixtures is triaged
coverage-gate                → stage: report, dependencies: [test, mobile-test] — merges
                              every target/jacoco-artifacts/*.exec (jacoco:merge@jacoco-
                              merge), then jacoco:check@jacoco-check enforces 50% line
                              coverage on com.automation.core.* against the union. Merged
                              HTML report published as an artifact
report                       → merges every job's Allure results into one report, publishes
                              it + the Extent report under public/, prints a JUnit summary
pages                        → publishes public/ to GitLab Pages
```

### Variables you can override per run
```
SUITE_TYPE          = regression (or smoke)
BROWSER             = chrome     (default for jobs that don't matrix over browser)
HEADLESS            = true       (always true on CI)
RETRY_COUNT         = 0
SECURITY_FAIL_CVSS  = 11         (OWASP Dependency-Check fail threshold — 11 = report-only)
```

The pipeline runs on every push to any branch, plus merge requests (`only:
branches, merge_requests` on each job) — not just `main`/`master`.

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
