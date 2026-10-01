package com.automation.mobile.crawler;

import com.automation.core.ai.OllamaClient;
import com.automation.core.crawler.CrawlIssue;
import com.automation.core.crawler.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Mobile counterpart to the web bug crawler's {@code AiPageReviewer} —
 * optional per-screen AI pass ({@code crawler.mobile.ai.enabled}, off by
 * default) that asks a text LLM to skim a trimmed Appium UI-hierarchy XML
 * dump for anomalies the fixed rule set in {@link MobileAppCrawler} doesn't
 * cover: leftover placeholder/debug text, a visible error dialog or stack
 * trace rendered into the screen, obviously mismatched or truncated
 * labels, etc.
 *
 * Purely additive and best-effort, same as every other AI pass in this
 * framework: any failure (unreachable AI server, unparsable reply) just
 * means this screen gets no AI findings — it never affects the rule-based
 * issues {@link MobileAppCrawler} already collected, and never fails the
 * crawl.
 */
final class AiMobileScreenReviewer {

    private static final Logger logger = LoggerFactory.getLogger(AiMobileScreenReviewer.class);
    private static final int MAX_SOURCE_CHARS = 6000;

    private static final String SYSTEM_PROMPT =
        "You are reviewing a single mobile app screen's UI-hierarchy XML (from Appium/UiAutomator2 for Android, "
            + "or XCUITest for iOS) for a QA bug-crawler. Rule-based checks already cover duplicate resource-ids, "
            + "missing accessibility labels on image/icon controls, and crash/ANR dialogs - do NOT repeat those. "
            + "Instead look for things only a reader would notice: leftover placeholder/debug/lorem-ipsum text, "
            + "a visible error message or stack trace rendered into the screen, obviously truncated or "
            + "mismatched labels, or text that looks like a raw untranslated resource key (e.g. "
            + "\"error.generic.title\") instead of real copy. Reply with ONLY a single JSON object, no prose "
            + "outside it, no markdown fence, shaped exactly as: {\"findings\": [{\"severity\": "
            + "\"error|warning|info\", \"description\": \"...\"}]} (findings may be an empty array if nothing is "
            + "worth flagging). Do not invent issues that aren't actually visible in the given XML.";

    private AiMobileScreenReviewer() {
    }

    static void review(String screenLabel, String pageSourceXml, PageResult result) {
        if (pageSourceXml == null || pageSourceXml.isBlank()) {
            return;
        }
        try {
            String prompt = "Screen: " + screenLabel + "\n\nUI hierarchy XML (trimmed):\n"
                + truncate(pageSourceXml, MAX_SOURCE_CHARS);
            String reply = OllamaClient.chat(SYSTEM_PROMPT, prompt);
            JsonNode json = OllamaClient.extractJsonObject(reply);
            if (json == null) {
                return;
            }
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
                result.addIssue(toSeverity(severityRaw), "ai-review", description);
            }
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler][AI] Screen review call failed for {} ({}) — continuing without it.",
                screenLabel, e.getMessage());
        }
    }

    private static CrawlIssue.Severity toSeverity(String raw) {
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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
    }
}
