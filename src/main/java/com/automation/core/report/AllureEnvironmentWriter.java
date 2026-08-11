package com.automation.core.report;

import com.automation.core.config.ConfigReader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Populates two Allure files that the plain allure-testng dependency does
 * NOT generate on its own, but that make the HTML report dramatically more
 * useful at a glance:
 *
 *  - environment.properties → powers the "Environment" widget on the
 *    report overview page (site/browser/OS/java/retry, same info already
 *    shown in the Extent report's system info panel).
 *  - categories.json        → powers the "Categories" tab, splitting
 *    failures into "Product defects" (assertion failures — the app is
 *    actually broken) vs "Test defects" (everything else — locator drift,
 *    timeouts, stale elements, framework/config problems). This is the
 *    single highest-value Allure feature for triaging a large regression
 *    run at a glance.
 *
 * Both are written once per JVM (guarded by a flag) into the configured
 * allure-results directory, before any test results land there, so Allure
 * picks them up on the next `mvn allure:report` / `allure:serve`.
 */
public final class AllureEnvironmentWriter {

    private static final Logger logger = LoggerFactory.getLogger(AllureEnvironmentWriter.class);
    private static volatile boolean written = false;

    private AllureEnvironmentWriter() {
    }

    public static synchronized void writeOnce() {
        if (written) {
            return;
        }
        written = true;

        // -Dallure.results.directory=... (same property allure-testng itself
        // reads) lets each parallel CI job/branch point at its own
        // subdirectory instead of everyone racing to write the same shared
        // file. Falls back to the previous hardcoded default.
        Path resultsDir = Paths.get(System.getProperty("allure.results.directory", "target/allure-results"));
        try {
            Files.createDirectories(resultsDir);
            writeEnvironmentProperties(resultsDir);
            writeCategories(resultsDir);
            writeExecutorInfo(resultsDir);
        } catch (IOException e) {
            logger.warn("Could not write Allure environment/categories/executor files: " + e.getMessage());
        }
    }

    /**
     * Populates executor.json — the file Allure's Trend/History/Duration/Retry widgets on the
     * report overview actually key their x-axis and "open in CI" links off. Without it those
     * widgets still render once history/ is carried over between runs, but every point is
     * labeled with an opaque internal counter and there's no link back to the build that
     * produced it. Detected from whichever CI system's standard env vars are present; falls
     * back to a plain local build label so `mvn test` outside CI still gets a valid file.
     */
    private static void writeExecutorInfo(Path resultsDir) throws IOException {
        String name;
        String type;
        String url;
        String buildOrder;
        String buildName;
        String buildUrl;

        if (System.getenv("JENKINS_URL") != null) {
            name = "Jenkins";
            type = "jenkins";
            url = System.getenv("JENKINS_URL");
            buildOrder = System.getenv().getOrDefault("BUILD_NUMBER", "0");
            buildName = "#" + buildOrder;
            buildUrl = System.getenv().getOrDefault("BUILD_URL", url);
        } else if (System.getenv("GITHUB_ACTIONS") != null) {
            name = "GitHub Actions";
            type = "github";
            String server = System.getenv().getOrDefault("GITHUB_SERVER_URL", "https://github.com");
            String repo = System.getenv().getOrDefault("GITHUB_REPOSITORY", "");
            url = server + "/" + repo;
            buildOrder = System.getenv().getOrDefault("GITHUB_RUN_NUMBER", "0");
            buildName = "Run #" + buildOrder;
            buildUrl = server + "/" + repo + "/actions/runs/" + System.getenv().getOrDefault("GITHUB_RUN_ID", "");
        } else if (System.getenv("GITLAB_CI") != null) {
            name = "GitLab CI";
            type = "gitlab";
            url = System.getenv().getOrDefault("CI_PROJECT_URL", "");
            buildOrder = System.getenv().getOrDefault("CI_PIPELINE_IID", "0");
            buildName = "Pipeline #" + buildOrder;
            buildUrl = System.getenv().getOrDefault("CI_PIPELINE_URL", url);
        } else {
            name = "Local";
            type = "local";
            url = "";
            buildOrder = String.valueOf(System.currentTimeMillis() / 1000);
            buildName = "Local run";
            buildUrl = "";
        }

        String site = ConfigReader.get("site", ConfigReader.getActiveSite());
        String json = """
                {
                  "name": "%s",
                  "type": "%s",
                  "url": "%s",
                  "buildOrder": %s,
                  "buildName": "%s — %s",
                  "buildUrl": "%s",
                  "reportName": "%s Allure Report"
                }
                """.formatted(name, type, url, buildOrder, buildName, site, buildUrl, site);

        Files.writeString(resultsDir.resolve("executor.json"), json, StandardCharsets.UTF_8);
    }

    /** Mirrors ExtentManager.reset() — call after a suite finishes so a second
     *  site run in the same JVM rewrites environment.properties with its own
     *  site/browser instead of keeping the first site's values. */
    public static synchronized void reset() {
        written = false;
    }

    private static void writeEnvironmentProperties(Path resultsDir) throws IOException {
        Path file = resultsDir.resolve("environment.properties");
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("Site=" + ConfigReader.get("site", ConfigReader.getActiveSite()) + System.lineSeparator());
            w.write("Browser=" + ConfigReader.get("browser", "chrome") + System.lineSeparator());
            w.write("Headless=" + ConfigReader.get("headless", "false") + System.lineSeparator());
            w.write("Retry.Count=" + ConfigReader.get("retry.count", "0") + System.lineSeparator());
            w.write("OS=" + System.getProperty("os.name") + System.lineSeparator());
            w.write("Java.Version=" + System.getProperty("java.version") + System.lineSeparator());
            w.write("Human.Pause.Enabled=" + ConfigReader.get("human.pause.enabled", "true") + System.lineSeparator());
        }
    }

    private static void writeCategories(Path resultsDir) throws IOException {
        Path file = resultsDir.resolve("categories.json");
        String json = """
                [
                  {
                    "name": "Product defects",
                    "matchedStatuses": ["failed"],
                    "messageRegex": ".*(AssertionError|expected.*but.*was|Expected.*but.*was).*"
                  },
                  {
                    "name": "Element not found / stale",
                    "matchedStatuses": ["broken", "failed"],
                    "traceRegex": ".*(NoSuchElementException|StaleElementReferenceException|ElementClickInterceptedException|ElementNotInteractableException).*"
                  },
                  {
                    "name": "Timeouts",
                    "matchedStatuses": ["broken", "failed"],
                    "traceRegex": ".*(TimeoutException|WaitTimedOutException).*"
                  },
                  {
                    "name": "Driver / infrastructure issues",
                    "matchedStatuses": ["broken"],
                    "traceRegex": ".*(WebDriverException|SessionNotCreatedException|UnreachableBrowserException|NoSuchSessionException).*"
                  },
                  {
                    "name": "Ignored / skipped",
                    "matchedStatuses": ["skipped"]
                  }
                ]
                """;
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
