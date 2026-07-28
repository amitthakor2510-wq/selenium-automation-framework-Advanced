# CI/CD Pipelines

## 🔄 Jenkins CI/CD Setup

### One-time setup
1. `Manage Jenkins → Tools` → Add JDK named exactly `JDK17`
2. `Manage Jenkins → Tools` → Add Maven named exactly `Maven3`
3. `Manage Jenkins → Tools` → Add Allure Commandline named exactly `allure`
4. Install **HTML Publisher** plugin
5. Create Pipeline job → SCM: Git → Script Path: `Jenkinsfile`

### Build parameters
| Parameter | Options | Default | Purpose |
|---|---|---|---|
| `SUITE_TYPE` | regression / smoke | regression | Which suite type to run for each discovered site |
| `SITE` | ALL / site name | ALL | Run all sites or one specific |
| `BROWSER` | chrome / firefox / edge | chrome | Browser to use |
| `HEADLESS` | true / false | true | Show browser or not |
| `RETRY_COUNT` | integer | 0 | Retries for failed tests — 0 disables for CI speed |

### After build — where to look
```
Job → Build #N
├── Console Output      → full Maven logs, errors, test output
├── Test Results        → pass/fail count, failed test names
├── Extent Test Report  → custom HTML report tab
└── Artifacts           → download screenshots and HTML report
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

`.github/workflows/github-ci.yml` runs on every push/PR to `main`/`master` and is the only one of the three pipelines that also **publishes a live Allure report with trend history** to GitHub Pages.

```
build          → mvn clean compile test-compile (fails fast on compile errors)
test           → installs Chrome if missing, runs the regression suite headless,
                  uploads Allure results + Extent/screenshots/surefire as artifacts
allure-report  → downloads this run's Allure results + previous run's history
                  from the gh-pages branch, merges them for trend graphs,
                  deploys both the Allure report and the Extent report to
                  GitHub Pages under /allure-report and /extent-report
```

Defaults baked into the workflow (`env:` block) — override by editing the file, these aren't currently exposed as manual dispatch inputs:

| Variable | Default |
|---|---|
| `SITE` | `demoqa` |
| `SUITE_TYPE` | `regression` |
| `BROWSER` | `chrome` |
| `HEADLESS` | `true` |
| `RETRY_COUNT` | `0` |

The `allure-report` job needs `contents: write` permission and a `gh-pages` branch to accumulate history in — first run will simply start fresh history if that branch doesn't exist yet.

---

## 🦊 GitLab CI/CD Pipeline

### Pipeline stages
```
build  → mvn compile — catches syntax errors before wasting time on tests
test   → mvn test with all -D flags — installs Chrome if missing on runner
report → publishes JUnit XML, archives HTML report and screenshots
```

### Variables you can override per run
```
SITE        = demoqa     (or ALL)
SUITE_TYPE  = regression (or smoke)
BROWSER     = chrome     (or firefox, edge)
HEADLESS    = true       (always true on CI)
```

### View report after pipeline
```
GitLab Job → Browse Artifacts → target/extent-reports/demoqa-report.html
```
Download and open in Chrome — full styling works when opened locally.

---

