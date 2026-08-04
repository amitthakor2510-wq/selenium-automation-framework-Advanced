# =============================================================================
# selenium-automation-framework — containerized test runner
# Builds the framework and runs it against browser nodes provided by
# docker-compose.yml (Selenium Grid: selenium-hub + chrome/firefox/edge nodes).
# This image does NOT bundle a browser itself — it drives remote browsers
# over Selenium Grid (RemoteWebDriver), which keeps the image small and
# lets the browser versions be upgraded independently via docker-compose.
# =============================================================================

FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /workspace

# Cache dependencies first so `docker compose build` is fast on code-only changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY . .

# -----------------------------------------------------------------------------
# Runtime stage — keeps the final image lean (no need for the full Maven
# local repo layer to persist beyond the build cache).
# -----------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17

WORKDIR /workspace
COPY --from=build /workspace /workspace
COPY --from=build /root/.m2 /root/.m2

# SECURITY: run as a dedicated non-root user rather than the image's
# default root. Nothing in this image needs root (no OS packages are
# installed at runtime, browsers live in the separate selenium/node-*
# containers over the network per docker-compose.yml) — running the test
# JVM as root here just widens the blast radius if a dependency (of which
# this project has many: Selenium, Appium, POI, Jackson, etc.) is ever
# compromised, for no corresponding benefit.
#
# The cached .m2 repo is MOVED (not just chown'd in place) from /root/.m2
# to the new user's own home: /root itself is mode 700 owned by root, so
# even after chown -R on its *contents*, a non-root user still can't
# traverse into /root to reach them — the directory entry point itself
# blocks it. Moving avoids doubling image size the way a copy would.
RUN groupadd --gid 1000 automation \
    && useradd --uid 1000 --gid automation --shell /bin/bash --create-home automation \
    && chown -R automation:automation /workspace \
    && mv /root/.m2 /home/automation/.m2 \
    && chown -R automation:automation /home/automation/.m2

USER automation
# useradd --create-home already set /home/automation as this user's home
# directory (getpwnam-level), but HOME is set explicitly too since some
# tools/shells only trust the environment variable, not a directory lookup.
# Maven resolves its default local repo as ${HOME}/.m2/repository, so this
# is what actually makes `mvn` (run below as user automation) find the
# dependency cache moved above instead of trying — and failing, since it
# can no longer read /root — to fall back to /root/.m2.
ENV HOME=/home/automation

# Defaults — overridden via `docker compose run -e` or -D system props.
# grid.enabled=true + grid.url tell DriverFactory to build a RemoteWebDriver
# pointing at the selenium-hub service instead of a local browser binary.
ENV SITE=demoqa \
    BROWSER=chrome \
    HEADLESS=true \
    GRID_ENABLED=true \
    GRID_URL=http://selenium-hub:4444/wd/hub \
    SUITE=testng-suites/demoqa-smoke.xml

ENTRYPOINT ["sh", "-c", "mvn -B test \
    -Dsite=$SITE \
    -Dbrowser=$BROWSER \
    -Dheadless=$HEADLESS \
    -Dgrid.enabled=$GRID_ENABLED \
    -Dgrid.url=$GRID_URL \
    -DsuiteXmlFile=$SUITE"]
