# Running the framework in Docker

Two new files at the project root — `Dockerfile` and `docker-compose.yml` — let
you run the whole suite against a disposable Selenium Grid instead of a
locally installed browser. `.dockerignore` keeps the build context small.

## What it spins up
- `selenium-hub` — the Grid router
- `chrome`, `firefox`, `edge` — one node per browser (each exposes a noVNC
  viewer so you can watch a run live)
- `tests` — builds this project and runs `mvn test` against the hub

## Quick start

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
`target/extent-reports`, `target/surefire-reports`, and `target/debug-dumps`
via the volume mounts in `docker-compose.yml`, so `mvn allure:serve` still
works locally after a containerized run.

## How it connects (what changed in the framework itself)
- `DriverFactory.createDriver()` now checks `grid.enabled`. When true, it
  builds a `RemoteWebDriver` pointed at `grid.url` instead of launching a
  local browser binary — same `ChromeOptions`/`FirefoxOptions`/`EdgeOptions`
  as the local path, just handed to the Grid instead of `ChromeDriver` etc.
- `global.properties` gained `grid.enabled=false` and
  `grid.url=http://localhost:4444/wd/hub` as defaults, overridable with
  `-Dgrid.enabled=true -Dgrid.url=...` the same way every other config key
  works.
- The Docker image itself never installs a browser — it only drives the
  Grid — so the image stays small and browser versions upgrade independently
  by bumping the `selenium/node-*` image tags in `docker-compose.yml`.

## Running without Docker Compose (single container against an existing grid)
```bash
docker build -t selenium-framework .
docker run --rm \
  -e GRID_URL=http://<your-grid-host>:4444/wd/hub \
  -e SITE=demoqa -e BROWSER=chrome \
  selenium-framework
```
