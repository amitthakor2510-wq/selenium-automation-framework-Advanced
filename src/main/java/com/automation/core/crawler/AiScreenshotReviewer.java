package com.automation.core.crawler;

import com.automation.core.ai.AiVisionClient;
import com.automation.core.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Optional visual AI pass shared by both bug crawlers (web:
 * {@code crawler.ai.vision.enabled}, in {@code SiteCrawler}; mobile:
 * {@code crawler.mobile.ai.vision.enabled}, in
 * {@code com.automation.mobile.crawler.MobileAppCrawler}) — sends a
 * screenshot of the current page/screen to a vision-capable LLM
 * ({@link AiVisionClient}) and asks it to flag things only a sighted
 * reviewer would notice: overlapping or cut-off text, elements running off
 * the visible area, obviously broken/unstyled layout, blank areas where
 * content is clearly expected, illegible text, or duplicated UI elements
 * stacked on each other.
 *
 * This complements — never replaces — the DOM/XML-based rule checks in
 * each crawler, which read markup/attributes and can't see how a page or
 * screen actually renders (a React/CSS layout bug or a mis-measured native
 * view can look perfectly fine in the DOM/hierarchy dump while being
 * visibly broken on screen).
 *
 * Same safety posture as every other AI feature in this framework:
 * report-only, best-effort, never fails the crawl. Any failure (vision
 * model not configured/reachable, unparsable reply) just means this
 * page/screen gets no visual findings.
 */
public final class AiScreenshotReviewer {

    private static final Logger logger = LoggerFactory.getLogger(AiScreenshotReviewer.class);

    private static final String SYSTEM_PROMPT =
        "You are visually reviewing ONE screenshot of a web page or mobile app screen for a QA bug-crawler. "
            + "Rule-based checks already cover markup-level issues (broken links, missing alt text, console "
            + "errors, duplicate ids) - do NOT repeat those, you cannot see them anyway. Instead, look ONLY at "
            + "what is visibly wrong in the image itself: overlapping or cut-off text, elements running off the "
            + "edge of the screen, obviously broken/unstyled layout, blank areas where content is clearly "
            + "expected, illegible text (too small or low contrast), or duplicated UI elements stacked on top of "
            + "each other. Do not guess at functionality or data correctness you cannot see. Reply with ONLY a "
            + "single JSON object, no prose outside it, no markdown fence, shaped exactly as: {\"findings\": "
            + "[{\"severity\": \"error|warning|info\", \"description\": \"...\"}]} (findings may be an empty "
            + "array if nothing looks visibly wrong).";

    private AiScreenshotReviewer() {
    }

    /**
     * @param label       short human-readable identifier for what was
     *                    screenshotted (a URL for the web crawler, a
     *                    screen/activity signature for the mobile crawler)
     *                    — used only in the prompt, never parsed.
     * @param base64Png   screenshot bytes, base64-encoded, as returned by
     *                    {@code TakesScreenshot.getScreenshotAs(OutputType.BASE64)}.
     * @param result      the page/screen result to attach findings to.
     */
    public static void review(String label, String base64Png, PageResult result) {
        if (base64Png == null || base64Png.isBlank() || !AiVisionClient.isConfigured()) {
            return;
        }
        try {
            String userPrompt = "Screenshot of: " + label;
            String reply = AiVisionClient.review(SYSTEM_PROMPT, userPrompt, base64Png);
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
                result.addIssue(toSeverity(severityRaw), "ai-visual-review", description);
            }
        } catch (Exception e) {
            logger.debug("[AiScreenshotReviewer] Visual review call failed for {} ({}) — continuing without it.",
                label, e.getMessage());
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
}
