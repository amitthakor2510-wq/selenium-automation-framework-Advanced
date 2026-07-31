# 🐳 Running the Framework in Docker

Two new files at the project root — `Dockerfile` and `docker-compose.yml` — let
you run the whole suite against a disposable Selenium Grid instead of a
locally installed browser. `.dockerignore` keeps the build context small.

## 📋 Table of Contents
- [What It Spins Up](#-what-it-spins-up)
- [Quick Start](#-quick-start)
- [How It Connects](#-how-it-connects-what-changed-in-the-framework-itself)
- [Running Without Docker Compose](#-running-without-docker-compose-single-container-against-an-existing-grid)

## 🧩 What It Spins Up
- `selenium-hub` — the Grid router
- `chrome`, `firefox`, `edge` — one node per browser (each exposes a noVNC
  viewer so you can watch a run live)
- `tests` — builds this project and runs `mvn test` against the hub

## ⚡ Quick Start

```bash
# 1. Start the grid (leave it running across multiple test runs)
docker compose up -d selenium-hub chrome firefox edge

# 2. Run the default suite (demoqa smoke, chrome, headless)
docker compose run --rm tests

# 3. Run something else by overriding env vars
docker compose run --rm \
  -e SITE=saucedemo \
  -e BROWSER=firefox \
  -e SUITE=testng-suites/saucedemo-regression.xml \
  tests

# 4. Watch a run live (Chrome node, password: secret)
open http://localhost:7900        # firefox: 7901, edge: 7902

# 5. Tear down
docker compose down -v
```

Reports land back on the host under `target/allure-results`,
`target/extent-reports`, `target/surefire-reports`, `target/debug-dumps`, and
`target/screenshots` via the volume mounts in `docker-compose.yml`, so
`mvn allure:serve` still works locally after a containerized run.

## 🔌 How It Connects (What Changed in the Framework Itself)
- `DriverFactory.createDriver()` now checks `grid.enabled`. When true, it
  builds a `RemoteWebDriver` pointed at `grid.url` instead of launching a
  local browser binary — same `ChromeOptions`/`FirefoxOptions`/`EdgeOptions`
  as the local path, just handed to the Grid instead of `ChromeDriver` etc.
- `global.properties` gained `grid.enabled=false` and
  `grid.url=http://localhost:4444/wd/hub` as defaults, overridable with
  `-Dgrid.enabled=true -Dgrid.url=...` the same way every other config key
  works.
- `pom.xml` declares matching `grid.enabled`/`grid.url` defaults under
  `<properties>` and forwards both through the surefire plugin's
  `<systemPropertyVariables>`. This step is what actually gets the
  Dockerfile's `-Dgrid.enabled=$GRID_ENABLED -Dgrid.url=$GRID_URL` from
  Maven's own process into the forked JVM that runs the tests — the same
  pattern already used for `site`, `browser`, `headless`, etc. Without it,
  the container would silently ignore `GRID_ENABLED=true` and try to
  launch a local Chrome binary that isn't in the image.
- The Docker image itself never installs a browser — it only drives the
  Grid — so the image stays small and browser versions upgrade independently
  by bumping the `selenium/node-*` image tags in `docker-compose.yml`.

## 🧱 Running Without Docker Compose (Single Container Against an Existing Grid)
```bash
docker build -t selenium-framework .
docker run --rm \
  -e GRID_URL=http://<your-grid-host>:4444/wd/hub \
  -e SITE=demoqa -e BROWSER=chrome \
  selenium-framework
```
