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

# CaptchaSolver (Tess4J) needs the native tesseract-ocr binary + trained
# language data on the image itself — Tess4J is a JNI wrapper, it doesn't
# bundle Tesseract. Without this, every SOLVE_TEXT_CAPTCHA/SOLVE_MATH_CAPTCHA
# step fails inside the container even once the Java-side datapath bug is
# fixed, since there's simply no tesseract install for it to find.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

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

# ROOT CAUSE FIX ("could not create parent directories" writing
# target/classes/... on `docker compose run --rm tests`, even though the
# compile itself has no real error): docker-compose.yml bind-mounts several
# HOST directories onto subpaths of /workspace/target (allure-results,
# extent-reports, surefire-reports, debug-dumps, screenshots) plus
# /workspace/self-healing-data. target/ is excluded by .dockerignore, so it
# doesn't exist anywhere in this image — on a fresh checkout, none of those
# host-side directories exist yet either. When `docker compose run` first
# sets up those bind mounts, the container runtime (running as root, before
# the USER automation directive below ever takes effect for this specific
# mechanism) auto-creates the missing mount-point directories INSIDE THE
# CONTAINER, including the /workspace/target parent itself — as root:root,
# mode 0755. USER automation (uid 1000) then has no write permission on
# that root-owned target/ to create target/classes/... (a sibling, non-
# mounted path Maven needs), even though it owns everything else under
# /workspace. Pre-creating target/ AND the exact bind-mount subdirectories
# here — before the chown -R above runs — means they already exist as
# real, automation-owned directories in the image itself, so the bind
# mounts attach to (and, for the still-missing host source dirs on a fresh
# checkout, inherit into) an already-correctly-owned tree instead of
# triggering root auto-creation. NOTE: this doesn't guarantee every host's
# corresponding ./target/<subdir> paths are writable by uid 1000 too (that
# depends on what already exists on the HOST side) — if a permission error
# still surfaces specifically inside one of the mounted report
# subdirectories (not target/classes, which this fully fixes), run on the
# host once: `mkdir -p target/{allure-results,extent-reports,surefire-reports,debug-dumps,screenshots} self-healing-data && chmod -R a+rwX target self-healing-data`
RUN mkdir -p /workspace/target/allure-results \
        /workspace/target/extent-reports \
        /workspace/target/surefire-reports \
        /workspace/target/debug-dumps \
        /workspace/target/screenshots \
        /workspace/self-healing-data \
    && chown -R automation:automation /workspace/target /workspace/self-healing-data

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
