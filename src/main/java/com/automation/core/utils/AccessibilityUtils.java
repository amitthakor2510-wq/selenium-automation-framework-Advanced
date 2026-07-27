package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import com.deque.html.axecore.results.Results;
import com.deque.html.axecore.results.Rule;
import com.deque.html.axecore.selenium.AxeBuilder;
import io.qameta.allure.Allure;
import org.openqa.selenium.WebDriver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Thin wrapper around axe-core (deque's WCAG/accessibility engine) for
 * Selenium. Relevant specifically because this framework tests government
 * portals subject to GIGW accessibility guidelines — axe-core covers a large
 * chunk of the same WCAG 2.1 criteria GIGW is built on, so a scan here is a
 * meaningful automated proxy for that manual accessibility review.
 *
 * Usage inside a test (any WebDriver page, no Page Object changes needed):
 *   AccessibilityUtils.assertNoViolations(driver, "TextBox page");
 *
 * Behaviour is controlled via config (global.properties or -D overrides),
 * so a WARN-only rollout is possible before turning it into a hard gate:
 *   a11y.enabled=true             - master switch (default true)
 *   a11y.failOn=critical,serious  - comma-separated impact levels that fail
 *                                    the test (default: critical,serious)
 *                                    set to a level not returned by axe
 *                                    (e.g. "none") to log-only, never fail.
 */
public final class AccessibilityUtils {

    private static final Logger logger = Logger.getLogger(AccessibilityUtils.class.getName());

    private AccessibilityUtils() {
    }

    /**
     * Runs an axe-core scan of the current page and, if violations at or
     * above the configured impact threshold are found, attaches a full
     * report to Allure and fails the test with a concise summary.
     */
    public static void assertNoViolations(WebDriver driver, String pageName) {
        if (!ConfigReader.getBoolean("a11y.enabled", true)) {
            logger.info("[AccessibilityUtils] a11y.enabled=false — skipping scan for '" + pageName + "'");
            return;
        }

        Results results = new AxeBuilder().analyze(driver);
        List<Rule> violations = results.getViolations();

        attachReportToAllure(pageName, results);

        Set<String> failOnImpacts = Set.of(
            ConfigReader.get("a11y.failOn", "critical,serious").toLowerCase().split("\\s*,\\s*"));

        List<Rule> blocking = violations.stream()
            .filter(rule -> rule.getImpact() != null && failOnImpacts.contains(rule.getImpact().toLowerCase()))
            .collect(Collectors.toList());

        if (!violations.isEmpty()) {
            logger.warning("[AccessibilityUtils] '" + pageName + "' — " + violations.size()
                + " axe-core violation rule(s) found (" + blocking.size() + " at/above fail threshold "
                + failOnImpacts + "): " + summarize(violations));
        }

        if (!blocking.isEmpty()) {
            throw new AssertionError("[AccessibilityUtils] '" + pageName + "' failed accessibility scan — "
                + blocking.size() + " violation(s) at impact level " + failOnImpacts + ": "
                + summarize(blocking) + ". Full axe report attached to Allure.");
        }
    }

    private static String summarize(List<Rule> rules) {
        return rules.stream()
            .map(r -> r.getId() + " [" + r.getImpact() + "] (" + r.getNodes().size() + " node(s)): " + r.getHelp())
            .collect(Collectors.joining("; "));
    }

    private static void attachReportToAllure(String pageName, Results results) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Accessibility scan — ").append(pageName).append('\n');
            sb.append("URL: ").append(results.getUrl()).append('\n');
            sb.append("Violations: ").append(results.getViolations().size())
                .append(" | Passes: ").append(results.getPasses().size())
                .append(" | Incomplete: ").append(results.getIncomplete().size()).append("\n\n");

            for (Rule rule : results.getViolations()) {
                sb.append("- [").append(rule.getImpact()).append("] ").append(rule.getId())
                    .append(" — ").append(rule.getHelp()).append('\n')
                    .append("  Affected nodes: ").append(rule.getNodes().size()).append('\n')
                    .append("  More info: ").append(rule.getHelpUrl()).append("\n\n");
            }

            Allure.addAttachment(
                "Accessibility Report — " + pageName,
                "text/plain",
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                "txt"
            );
        } catch (Exception e) {
            // Reporting is best-effort — never let an Allure attachment failure
            // mask the real accessibility result.
            logger.warning("[AccessibilityUtils] Could not attach axe report to Allure: " + e.getMessage());
        }
    }
}
