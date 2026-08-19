<div align="center">

# ⚙️ Configuration & Environments

</div>

---

## 📋 Table of Contents
- [🔧 Configuration — `global.properties`](#-configuration--globalproperties)
- [🐳 Running Against a Dockerized Selenium Grid](#-running-against-a-dockerized-selenium-grid)
- [🧭 Safari](#-safari)

---

## 🔧 Configuration — `global.properties`

```properties
# ── Browser ────────────────────────────────────────────────────────
browser=chrome          # chrome | firefox | edge | brave | safari
headless=false           # true = no visible window (use true on CI/CD)
                          # (ignored for safari — see 🧭 Safari below)

# ── Timeouts ───────────────────────────────────────────────────────
timeout=10               # seconds to wait for elements before failing
                          # (per-site overrides live in <site>.properties —
                          #  see demoqa.properties, bumped to 20s for its
                          #  slower-rendering book store page)
timeout.long=15          # seconds to wait for elements with known delays
                          # (timer alerts, dynamic buttons)

# ── File Upload/Download ──────────────────────────────────────────
download.folder.path=target/downloads       # where downloaded files land
upload.file.path=target/test-upload.txt     # file used by upload tests — must exist
download.wait.seconds=10                    # how long UploadDownloadPage.clickDownloadAndVerify() polls
                                             # for a downloaded file before giving up (raise on slow CI/large files)

# ── Retry ──────────────────────────────────────────────────────────
retry.count=2             # automatic re-runs of a failed test (see Retry & Resilience)

# ── Human Pause ────────────────────────────────────────────────────
human.pause.enabled=true       # false = skip all pauses for fast runs
human.pause.min=100            # min ms before each click/type action
human.pause.max=300            # max ms before each click/type action
human.pause.postTest.min=150   # min ms after each test finishes
human.pause.postTest.max=400   # max ms after each test finishes
human.pause.typing.min=10      # min ms between keystrokes when typing
human.pause.typing.max=30      # max ms between keystrokes when typing

# ── Selenium Grid / Docker ────────────────────────────────────────
grid.enabled=false                              # true = drive a RemoteWebDriver against grid.url instead of a local browser
grid.url=http://localhost:4444/wd/hub           # Selenium Grid hub endpoint (see Dockerized Selenium Grid below)

# ── Data-Driven Testing (DDT) Filters ─────────────────────────────
data.tags=                          # comma-separated tag filter, e.g. -Ddata.tags=smoke,regression
                                     # (blank = run every row regardless of its "tags" column)
data.execute.column=execute         # column DataProvider checks to skip a row (no/false/0/skip = excluded)

# ── Self-Healing Locators (see core/selfhealing/SelfHealingEngine.java) ──
self-healing.enabled=true                                      # master switch — false falls back to plain fail-on-first-miss
self-healing.threshold=0.55                                     # min similarity (0.0-1.0) a candidate must reach to be accepted
self-healing.repository.path=self-healing-data/locator-repository.json  # known-good fingerprints, persisted across runs
self-healing.report.path=target/self-healing/healing-report.json        # end-of-run summary of every locator that had to be healed
self-healing.visual.enabled=false                                # opt-in screenshot-hash fallback when DOM scoring alone misses
self-healing.visual.weight=0.5                                   # 0.0 = visual stage never influences the outcome; 1.0 = DOM score is ignored once visual healing kicks in

# ── CAPTCHA / Page Load (see core/utils/CaptchaSolver.java, core/keyword/KeywordEngine.java) ──
captcha.autoDetect.enabled=true     # master switch for Mode 1 (automatic) CAPTCHA detect/solve — see docs/CAPTCHA_SOLVER.md
captcha.wait.seconds=               # blank = falls back to timeout.long; how long the SOLVE_TEXT_CAPTCHA_IF_PRESENT
                                     # keyword waits for a CAPTCHA image to appear before treating this run as
                                     # "no CAPTCHA" and continuing without failing the test
captcha.segmentation.caseHeightRatio=0.78  # height ratio below which a shape-symmetric letter (C/O/S/U/V/W/X/Z)
                                            # is corrected to lower-case — see docs/CAPTCHA_SOLVER.md. The full set
                                            # of captcha.segmentation.* keys lives in global.properties.
captcha.expected.length=0           # blank/0 = disabled; static fallback CAPTCHA length used only when the
                                     # answer field has no HTML maxlength attribute — the field's own maxlength
                                     # is read automatically per-call and takes priority. See docs/CAPTCHA_SOLVER.md.
pageLoad.timeout=                   # blank = falls back to timeout.long; how long the WAIT_FOR_PAGE_LOAD keyword
                                     # waits for document.readyState == 'complete' before logging a warning and
                                     # continuing anyway (never fails the test on its own)

# ── API Test Logging (see core/api/ApiClient.java) ────────────────
api.log.onFailureOnly=true          # true = only print request/response bodies when an assertion fails

# ── Video Recording (see core/utils/VideoRecorder.java) ───────────
video.enabled=false                 # true = record each test's screen (needs headless=false + a real/virtual display)
video.fps=10                        # capture rate
video.keep.on.pass=false            # true = keep a passing test's recording instead of deleting it after the run
video.output.dir=target/videos      # where raw per-test videos land before being attached to Allure/Extent
```

Any key can be overridden at runtime:
```bash
mvn test -Dbrowser=edge -Dheadless=true -Dhuman.pause.enabled=false -Dretry.count=0
```

---

## 🐳 Running Against a Dockerized Selenium Grid

`docker-compose.yml` spins up a full Selenium Grid (hub + Chrome/Firefox/Edge nodes) plus a containerized test runner — no local browser installs needed, and you can **watch the tests run live** via noVNC.

```bash
# 1. Start the grid (hub + all three browser nodes)
docker compose up -d selenium-hub chrome firefox edge

# 2. Run the demoqa smoke suite against it (defaults: chrome, headless)
docker compose run --rm tests

# 3. Run a different site/browser/suite combination
docker compose run --rm \
  -e BROWSER=firefox \
  -e SITE=saucedemo \
  -e SUITE=testng-suites/saucedemo-regression.xml \
  tests

# 4. Watch a node live (password: secret)
open http://localhost:7900   # chrome noVNC — firefox on 7901, edge on 7902

# 5. Watch the grid console
open http://localhost:4444/ui

# 6. Tear everything down
docker compose down -v
```

> [!NOTE]
> Reports, screenshots, Allure results, and `target/debug-dumps/` are all volume-mounted back to your host `target/` folder, so they're available after the container exits exactly as they would be from a local `mvn test` run.

> [!WARNING]
> Safari has no Grid node image (see the comment at the top of `docker-compose.yml`) and cannot run through this compose file — use the local (non-Grid) path documented below instead.

---

## 🧭 Safari

Safari is fully supported by `DriverFactory`, but it has real platform constraints the other four browsers don't, so it's driven a little differently:

| Constraint | Why | What it means for you |
|---|---|---|
| **macOS only** | `safaridriver` ships inside the OS — there's no Linux/Windows build, and `WebDriverManager` has nothing to download for it | Local runs and CI runs both need a real Mac |
| **One-time enable** | Remote Automation is off by default | Run `safaridriver --enable` once per machine (`sudo` on a local Mac; not needed on a hosted macOS CI runner) before the first session — otherwise every run fails fast with `Could not create a session` |
| **No headless mode** | WebKit's automation surface has no equivalent of `--headless` | `-Dheadless=true` is accepted but ignored — `DriverFactory` logs a warning and opens a normal windowed session anyway |
| **One session per machine** | A WebKit/Apple limitation, not a Selenium one | Never point a `parallel="classes"`/`thread-count>1` suite at `browser=safari` — use the dedicated `testng-suites/<site>-safari-<suite>.xml` files, which are `parallel="none"` |

```bash
# One-time setup (macOS)
safaridriver --enable

# Run it
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-safari-smoke.xml -Dbrowser=safari
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-safari-regression.xml -Dbrowser=safari
```

> [!CAUTION]
> Downloaded files land in the signed-in user's real `~/Downloads` — there's no per-session download-directory isolation the way `DriverFactory.getDownloadPath()` gives Chrome/Brave/Edge, so `UploadDownloadTest`-style assertions on a specific download path aren't portable to Safari as-is.

**CI:** Safari is opt-in and runs on a separate macOS agent/runner in the GitHub Actions pipeline only — Jenkins and GitLab CI don't run it (no macOS agent/runner registered in either) — see [🧭 Safari](ci-cd.md#-safari) in the CI/CD guide.

<div align="center">

<sub>⬆️ <a href="#️-configuration--environments">Back to top</a> · <a href="../README.md">← Back to README</a></sub>

</div>
