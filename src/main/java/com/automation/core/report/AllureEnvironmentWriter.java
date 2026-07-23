package com.automation.core.report;

import com.automation.core.config.ConfigReader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

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

    private static final Logger logger = Logger.getLogger(AllureEnvironmentWriter.class.getName());
    private static volatile boolean written = false;

    private AllureEnvironmentWriter() {
    }

    public static synchronized void writeOnce() {
        if (written) return;
        written = true;

        Path resultsDir = Paths.get("target", "allure-results");
        try {
            Files.createDirectories(resultsDir);
            writeEnvironmentProperties(resultsDir);
            writeCategories(resultsDir);
        } catch (IOException e) {
            logger.warning("Could not write Allure environment/categories files: " + e.getMessage());
        }
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