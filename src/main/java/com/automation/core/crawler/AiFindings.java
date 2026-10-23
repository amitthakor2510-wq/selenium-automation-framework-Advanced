package com.automation.core.crawler;

import com.automation.core.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Shared helpers for the AI reviewers ({@code AiPageReviewer},
 * {@code AiScreenshotReviewer}, {@code AiChecklistReviewer} and the mobile
 * {@code AiMobileScreenReviewer}): trimming model input, mapping a severity
 * word to {@link CrawlIssue.Severity}, and turning the standard
 * {@code {"findings": [{"severity": ..., "description": ...}]}} reply into
 * crawl issues. Public only because the mobile reviewer lives in another
 * package.
 */
public final class AiFindings {

    private AiFindings() {
    }

    /** One model call that returns the raw reply text; may throw. */
    @FunctionalInterface
    public interface ReplySupplier {
        String get() throws Exception;
    }

    /**
     * Runs {@code call} and parses the reply's JSON object. Best-effort by
     * design: any failure (unreachable AI server, unparsable reply) is logged
     * at debug and yields {@code null}, so callers just return on null.
     *
     * @param logPrefix e.g. {@code "[SiteCrawler][AI]"}
     * @param what      e.g. {@code "Page review call"}
     * @param label     page URL / screen label, used only in the log line
     */
    public static JsonNode askForJson(ReplySupplier call, Logger logger, String logPrefix,
                                      String what, String label) {
        try {
            return OllamaClient.extractJsonObject(call.get());
        } catch (Exception e) {
            logger.debug("{} {} failed for {} ({}) — continuing without it.",
                logPrefix, what, label, e.getMessage());
            return null;
        }
    }

    /** Cuts {@code s} to {@code max} characters, marking the cut. */
    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
    }

    /** "error" / "warning" map to themselves; null and anything else is INFO. */
    public static CrawlIssue.Severity toSeverity(String raw) {
        if (raw == null) {
            return CrawlIssue.Severity.INFO;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "error":
                return CrawlIssue.Severity.ERROR;
            case "warning":
                return CrawlIssue.Severity.WARNING;
            default:
                return CrawlIssue.Severity.INFO;
        }
    }

    /**
     * Adds every well-formed entry of {@code json.findings} to {@code result}
     * under {@code category}. A missing or non-array {@code findings} field,
     * or an entry with no description, is silently skipped.
     */
    public static void addFindings(JsonNode json, String category, PageResult result) {
        JsonNode findings = json.get("findings");
        if (findings == null || !findings.isArray()) {
            return;
        }
        for (JsonNode finding : findings) {
            String severityRaw = finding.hasNonNull("severity") ? finding.get("severity").asText() : "info";
            String description = finding.hasNonNull("description") ? finding.get("description").asText() : null;
            if (description == null || description.isBlank()) {
                continue;
            }
            result.addIssue(toSeverity(severityRaw), category, description);
        }
    }
}
