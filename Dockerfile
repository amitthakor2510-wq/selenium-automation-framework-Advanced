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
