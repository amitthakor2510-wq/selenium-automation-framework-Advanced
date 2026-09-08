# All Commands — selenium-automation-framework Advanced

Every literal command used anywhere in this project: local runs, Docker, scripts,
Maven profiles, git hooks, and CI. Nothing summarized or left out — copy-paste
exactly as shown, then swap placeholders (`<...>`) for real values.

> **Note:** `demoqa` and `saucedemo` are currently set to
> `site.demoqa.enabled=false` / `site.saucedemo.enabled=false` in
> `pipeline-config.properties`. Every command below that targets them is kept
> for reference (and for CI, which reads the same file) but will fail locally
> with `SiteRegistry`'s "site is disabled" error until you re-enable them.

---

## 1. Prerequisites / version checks

```bash
java -version
mvn -version
./mvnw -version              # Linux/macOS — bundled wrapper, no local Maven needed
mvnw.cmd -version            # Windows equivalent
docker compose version
sudo safaridriver --enable   # macOS only, one-time, needed before any Safari suite
safaridriver --enable        # same, no sudo needed on a hosted macOS CI runner
npm install -g appium        # only for the mobile path
```

---

## 2. Local — Browser Tests (Maven / TestNG)

```bash
# Smoke suite — fastest sanity check (Chrome, visible browser)
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# Full regression suite
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml

# Single test class only
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dtest=ButtonsTest

# Different browser
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dbrowser=firefox
mvn test -Dbrowser=edge -Dheadless=true -Dhuman.pause.enabled=false -Dretry.count=0

# Safari (macOS only) — dedicated *-safari-*.xml suite required (parallel="none")
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-safari-regression.xml -Dbrowser=safari
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-safari-smoke.xml -Dbrowser=safari
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-safari-regression.xml -Dbrowser=safari

# Headless — no visible browser window
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dheadless=true

# saucedemo instead of demoqa
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-smoke.xml
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-regression.xml

# SAHMAT instead of demoqa
mvn test -Dsite=SAHMAT -DsuiteXmlFile=testng-suites/SAHMAT-smoke.xml
mvn test -Dsite=SAHMAT -DsuiteXmlFile=testng-suites/SAHMAT-regression.xml

# SAHMAT — every browser one at a time, own scoped reports + combined Allure at the end
Scripts/run-SAHMAT-all-browsers.sh regression
Scripts/run-SAHMAT-all-browsers.sh smoke
Scripts/run-SAHMAT-all-browsers.sh regression -Dheadless=false
HEADLESS=false Scripts/run-SAHMAT-all-browsers.sh smoke

# Slow mode — watch every action clearly
mvn test -Dhuman.pause.min=2000 -Dhuman.pause.max=3000 -Dtest=ButtonsTest

# Fast mode — disable all pacing pauses
mvn test -Dhuman.pause.enabled=false -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# Disable retry — see a failure immediately instead of it re-running
mvn test -Dretry.count=0 -Dtest=BookStoreApplicationTest

# CAPTCHA — disable auto-detect (see docs/CAPTCHA_SOLVER.md)
mvn test -Dsite=demoqa -Dbrowser=chrome
mvn test -Dsite=saucedemo -Dbrowser=chrome -DsuiteXmlFile=testng-suites/saucedemo-keyword-driven.xml
mvn test -Dcaptcha.autoDetect.enabled=false

# Any global.properties key can be overridden with -Dkey=value (docs/configuration.md)

# -Dsite defaults to "demoqa" (ConfigReader) if omitted entirely — both of
# these are equivalent to the smoke/regression commands above, just relying
# on that default instead of stating it:
mvn test -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
mvn test -DsuiteXmlFile=testng-suites/demoqa-regression.xml
```

---

## 3. Docker — Selenium Grid

```bash
# 1. Start the grid (hub + all three browser nodes)
docker compose up -d selenium-hub chrome firefox edge

# 1b. First time only — pre-create report folders as writable by container's uid 1000
mkdir -p target/{allure-results,extent-reports,surefire-reports,debug-dumps,screenshots}
chmod -R 777 target

# 2. Confirm it's healthy
docker compose ps

# 3. Run the default suite (demoqa smoke, Chrome, headless)
docker compose run --rm tests

# 4. Run a different browser/site/suite
docker compose run --rm \
  -e BROWSER=firefox \
  -e SITE=saucedemo \
  -e SUITE=testng-suites/saucedemo-regression.xml \
  tests

# 5. Watch a run live (turn OFF headless so the node renders something)
docker compose run --rm -e HEADLESS=false tests
open http://localhost:7900        # chrome — firefox: 7901, edge: 7902

# 6. Grid console (session/node status) — note the trailing slash
open http://localhost:4444/ui/

# 7. Tear everything down
docker compose down -v

# Single container against an existing/remote grid (no Compose)
docker build -t selenium-framework .
docker run --rm \
  -e GRID_URL=http://<your-grid-host>:4444/wd/hub \
  -e SITE=demoqa -e BROWSER=chrome \
  selenium-framework
```

---

## 4. Keyword-Driven & Data-Driven Tests

```bash
# Keyword-driven demoqa Text Box (CSV-scripted steps)
mvn test -Dtest=KeywordDrivenTextBoxTest

# Keyword-driven saucedemo login
mvn test -Dtest=KeywordDrivenLoginTest

# Data-driven saucedemo login (csv/json/xlsx/yaml/zip — same test, 5 formats)
mvn test -Dtest=LoginDataDrivenTest
```

---

## 5. REST API Tests

```bash
mvn test -Dtest=BookStoreApiTest
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests.xml
mvn test -Dsite=jsonplaceholder -DsuiteXmlFile=testng-suites/api-tests-jsonplaceholder.xml
```

---

## 6. Accessibility, Visual Regression & Performance (opt-in, not run in CI by default)

```bash
# Accessibility — axe-core scan
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml

# Visual regression — AShot pixel-diff vs. committed baseline
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-visual.xml

# Performance smoke — JMeter response-time/response-code check
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf
mvn test -Dsite=jsonplaceholder -DsuiteXmlFile=testng-suites/jsonplaceholder-perf.xml -Dgroups=perf

# tune the load profile
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-perf.xml -Dgroups=perf \
  -Dperf.threads=25 -Dperf.rampUpSeconds=10 -Dperf.iterations=10 -Dperf.maxP99Millis=3000

# JMeter via the perf Maven profile instead
mvn verify -Pperf
mvn verify -Pperf -Dthreads=10 -DrampUp=5 -Dloops=5 -DmaxResponseMs=3000

# Synthetic data generation for tests
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-synthetic-data.xml
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-synthetic-data.xml -Dsynthetic.data.count=10 -Dsynthetic.data.seed=12345
```
JMeter results land at `target/jmeter/results/` (raw) and `target/jmeter/reports/` (HTML).

---

## 7. Mobile (Appium)

```bash
# 1. Start the Appium server (separately installed)
appium

# 2. Copy the example config and fill in real values (edit mobile.device.name to match `adb devices`)
cp src/test/resources/config/mobile.properties.example \
   src/test/resources/config/mobile.properties
adb devices

# 3. Run
mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
```

```bash
# Genymotion (or any emulator with its own player window) — keep the window
# focused/visible so you can watch the run, Linux only (needs `wmctrl`,
# install via `sudo apt install wmctrl`). Cosmetic only — has no effect on
# the Appium session itself; skip entirely in CI or on a headless AVD.
wmctrl -l                              # find the exact window title (varies by device profile)
wmctrl -a "Google Pixel 7 - 13.0" && \
  mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml \
    -Dmobile.app.package=com.android.settings -Dmobile.app.activity=.Settings

# Real device over Wi-Fi (Android 11+) — Settings → Developer options →
# Wireless debugging → "Pair device with pairing code" first
adb pair 192.168.1.42:41253      # IP:port + code shown on the phone, then enter the 6-digit code when prompted
adb connect 192.168.1.42:5555    # the (different) connection port shown on the main Wireless debugging screen
adb devices                      # should now list the phone over Wi-Fi
```

---

## 8. Viewing Reports

```bash
# Extent — self-contained HTML, open directly
open target/extent-reports/demoqa-report.html        # macOS
xdg-open target/extent-reports/demoqa-report.html     # Linux

# Allure — interactive, with step-by-step timelines and history
allure serve target/allure-results
mvn allure:report                                      # writes target/allure-report/, no server
```

---

## 9. Segmented Allure Reports (by browser / site / test type / category)

```bash
# Splits one combined Allure report into a genuinely separate report per
# browser, site/app, test type (suite), severity, and category (TestNG
# group) — same script all three CI pipelines run after their own `mvn test`.
# Despite what docs/reports-and-quality.md said before this fix, the script
# lives under .github/workflows/scripts/, not Scripts/ — running it at the
# path the doc used to give would fail with "No such file or directory".
mvn test -Dsite=demoqa
python3 .github/workflows/scripts/generate_segmented_reports.py   # writes target/allure-segmented/ + target/report-index.html

# Same flags CI itself passes (see Jenkinsfile/github-ci.yml/.gitlab-ci.yml)
python3 .github/workflows/scripts/generate_segmented_reports.py --results-dir target/allure-results --skip-combined
```

---

## 10. Code Coverage & Code Quality

```bash
# Coverage — produced by every `mvn test`, no separate command needed
mvn test
open target/site/jacoco/index.html   # macOS

# Checkstyle — opt-in, NOT run by `mvn test`
mvn verify
mvn clean compile
mvn checkstyle:check
```

---

## 11. Security Scan (OWASP Dependency-Check) — opt-in `security` profile

```bash
mvn verify -Psecurity
# gate the build on High/Critical CVEs instead of report-only (default failBuildOnCVSS=11)
mvn -B verify -Psecurity -DfailBuildOnCVSS=7 -DnvdApiKey=$NVD_API_KEY -DskipTests
```
Report: `target/dependency-check-report.html` (+ JSON).

---

## 12. Mutation Testing (PIT) — opt-in `mutation` profile

```bash
mvn verify -Pmutation
```
Deliberately scoped to fast, pure-logic JUnit 5 unit tests (`DataRow`, CSV/JSON
data-file readers) — not wired into any CI pipeline, same as `perf`.

---

## 13. Plain JUnit 5 Unit Tests (core/tia + core/data readers) — opt-in `unit-tests` profile

```bash
mvn verify -Punit-tests
```
Runs the JUnit 5 regression suites that have no other way to execute: `core/tia`'s own
unit tests (`GitDiffReaderTest`, `SiteMapperTest`, `TestClassDetectorTest`,
`SourcePathResolverTest`, `ResourceReferenceIndexTest`, `CoverageMapTest`,
`ClassFileScannerAndDependencyGraphTest`, `UnsafeChangeRulesTest`,
`TestImpactAnalyzerIntegrationTest` — see docs/TEST_IMPACT_ANALYSIS.md) plus the same
`core/data/readers` tests `-Pmutation` above targets. Uses Failsafe (not Surefire) so it
never touches the TestNG provider pinned for the default `mvn test` — see the
`unit-tests` profile's own comment in pom.xml for why that separation matters. Not wired
into any CI pipeline yet, same as `perf`/`mutation`.

---

## 14. Test Impact Analysis (TIA) — run only tests affected by a code change

```bash
# See what would run, without running it
./Scripts/test-impact-analysis.sh --base origin/main

# Actually run it
./Scripts/test-impact-analysis.sh --base origin/main --run

# Compare two fixed commits
./Scripts/test-impact-analysis.sh --base origin/main --head HEAD --run
./Scripts/test-impact-analysis.sh --base HEAD~5 --run --browser firefox

# Or drive the analyzer directly via Maven
mvn -q compile test-compile
mvn -q exec:java@tia -Ptia -Dtia.base=origin/main
cat target/tia/impact-report.md
```

---

## 15. Coverage Map (which tests exercise which classes)

```bash
./Scripts/build-coverage-map.sh <site> [suiteXmlFile]
mvn -B -q exec:java@coverage-map -Pcoverage-map
```

---

## 16. Artifact / Video Retention Cleanup

```bash
./Scripts/prune-artifacts.sh                          # keep last 5 runs of target/videos
./Scripts/prune-artifacts.sh --dry-run                # show what would be deleted, delete nothing
./Scripts/prune-artifacts.sh --keep-runs 10           # keep more history
./Scripts/prune-artifacts.sh --dir target/videos --gap-minutes 15

# Or directly via Maven (what the script wraps)
mvn -q exec:java@prune-artifacts -Pprune-artifacts -Dprune.keepRuns=10 -Dprune.dryRun=true
```
Opt-in only — never bound to any lifecycle phase; `mvn test`/`mvn clean` never delete anything on their own.

---

## 17. ReportPortal (opt-in, local run against your own RP instance)

```bash
export RP_API_KEY=<your-api-key>
mvn test -Preportportal \
  -Dreportportal.endpoint=https://your-rp-instance.example.com \
  -Dreportportal.project=selenium-automation-framework \
  -Dsite=demoqa -Dbrowser=chrome
```

---

## 18. Site On/Off Switch (`pipeline-config.properties` reader)

```bash
Scripts/enabled-sites.sh                        # newline list of every enabled site
Scripts/enabled-sites.sh --browser-only         # same, minus "mobile" and any API-only site
Scripts/enabled-sites.sh --api-only             # only sites tagged site.<name>.type=api
Scripts/enabled-sites.sh --json                 # ["demoqa","saucedemo"] (empty -> [])
Scripts/enabled-sites.sh --browser-only --json
Scripts/enabled-sites.sh --api-only --json
Scripts/enabled-sites.sh --check <site>         # exit 0 if enabled, 1 if disabled/unknown
Scripts/enabled-sites.sh --dotenv               # SITE_<NAME>_ENABLED=true|false, one per line
```

---

## 19. Adding a New Site

```bash
./Scripts/new-site.sh <sitename> <base-url>
./Scripts/new-site.sh mysite https://mysite.com
mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-regression.xml
mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/api-tests-mysite.xml
mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-perf.xml -Dgroups=perf
```

---

## 20. Project Audit (package/directory consistency check)

```bash
Scripts/audit-project.sh                # run every check
Scripts/audit-project.sh --fix-packages  # also auto-move misplaced .java files
Scripts/audit-project.sh --quick         # skip mvn compile (fast, no network needed)
```

---

## 21. Git Hooks (Checkstyle + gitleaks on commit)

```bash
Scripts/install-hooks.sh                # one-time per clone: point git at .githooks/
git config --unset core.hooksPath       # uninstall — go back to git's default hooks
SKIP_HOOKS=1 git commit -m "..."        # bypass hooks for a single commit
git commit --no-verify -m "..."         # same bypass, git's own flag

# What the hook itself runs (for reference — you don't run these directly):
gitleaks protect --staged --redact -v
mvn -q checkstyle:check
```

---

## 22. gh-pages Retention (squash branch history)

```bash
# Runs on a schedule (Sundays 03:00 UTC) or manually via workflow_dispatch
# with an optional keep_commits input (default 50) in GitHub Actions —
# no local command; trigger it from the Actions tab or via gh CLI:
gh workflow run "gh-pages Retention (squash history)" -f keep_commits=50
```

---

## 23. CI/CD — Jenkins service management (server-side, one-time setup)

```bash
sudo systemctl edit jenkins
sudo systemctl daemon-reload
sudo systemctl restart jenkins
```

---

## 24. Git / Setup

```bash
git clone <repo-url> && cd selenium-automation-framework
```

---

## 25. What CI itself runs (for reference — these fire automatically, you don't type them)

```bash
# GitHub Actions / GitLab CI security-scan job
mvn -B verify -Psecurity -DfailBuildOnCVSS=$SECURITY_FAIL_CVSS -DnvdApiKey=$NVD_API_KEY -DskipTests
```

---

<sub>Generated from `README.md`, `RUNNING.md`, `DOCKER.md`, `KEYWORD_DRIVEN_TESTING.md`,
`CONVENTIONS.md`, every file under `docs/`, `src/main/java/com/automation/mobile/README.md`,
every script under `Scripts/` and `.github/workflows/scripts/`,
`.githooks/pre-commit`, `pom.xml` profiles, and the CI workflow files. If a
command isn't here, it isn't documented anywhere in the repo either — worth
adding wherever it actually lives rather than only in this file.</sub>
