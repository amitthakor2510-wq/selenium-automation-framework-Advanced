# Configuration & Environments

## 🔧 Configuration — `global.properties`

```properties
# ── Browser ────────────────────────────────────────────────────────
browser=chrome          # chrome | firefox | edge
headless=false           # true = no visible window (use true on CI/CD)

# ── Timeouts ───────────────────────────────────────────────────────
timeout=10               # seconds to wait for elements before failing
                          # (per-site overrides live in <site>.properties —
                          #  see demoqa.properties, bumped to 20s for its
                          #  slower-rendering book store page)

# ── Retry ──────────────────────────────────────────────────────────
retry.count=2             # automatic re-runs of a failed test (see Retry & Resilience)

# ── Human Pause ────────────────────────────────────────────────────
human.pause.enabled=true       # false = skip all pauses for fast runs
human.pause.min=400            # min ms before each click/type action
human.pause.max=1200           # max ms before each click/type action
human.pause.postTest.min=500   # min ms after each test finishes
human.pause.postTest.max=1500  # max ms after each test finishes
human.pause.typing.min=40      # min ms between keystrokes when typing
human.pause.typing.max=120     # max ms between keystrokes when typing
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

Reports, screenshots, Allure results, and `target/debug-dumps/` are all volume-mounted back to your host `target/` folder, so they're available after the container exits exactly as they would be from a local `mvn test` run.

---

