package com.automation.core.selfhealing;

import com.automation.core.config.ConfigReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes target/self-healing/healing-report.json — every locator that broke
 * and was recovered by similarity matching during the run. A test suite
 * that's all-green can still hide locators quietly drifting out from under
 * it; this report is what turns that into something a developer actually
 * sees, without failing the build over it. Mirrors AllureEnvironmentWriter's
 * "write once at end of run, from a static list of what happened" shape.
 */
final class SelfHealingReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(SelfHealingReportWriter.class);

    private SelfHealingReportWriter() {
    }

    static void write(List<HealingEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Path path = Paths.get(ConfigReader.get("self-healing.report.path",
            "target/self-healing/healing-report.json"));
        try {
            Files.createDirectories(path.getParent());
            LocatorRepository.mapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), events);
            logger.warn("[SelfHealing] " + events.size()
                + " locator(s) had drifted and were self-healed this run. See " + path
                + " — each entry is a real locator to fix, even though the test(s) still passed.");
        } catch (Exception e) {
            logger.warn("[SelfHealing] Could not write healing report to " + path + ": " + e.getMessage());
        }
    }
}
