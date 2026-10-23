package com.automation.core.crawler;

import com.automation.core.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Optional per-page AI pass for the bug crawler ({@code crawler.ai.enabled},
 * off by default) — asks a text LLM to skim a trimmed page source for
 * anomalies the fixed rule set in {@link SiteCrawler} doesn't cover
 * (leftover placeholder/lorem-ipsum text, visible error messages or stack
 * traces rendered into the page, obviously mismatched labels, etc.).
 *
 * Purely additive and best-effort: any failure (unreachable AI server,
 * unparsable reply) just means this page gets no AI findings — it never
 * affects the rule-based issues {@link SiteCrawler} already collected, and
 * never fails the crawl.
 */
final class AiPageReviewer {

    private static final Logger logger = LoggerFactory.getLogger(AiPageReviewer.class);
    private static final int MAX_PAGE_SOURCE_CHARS = 6000;

    private static final String SYSTEM_PROMPT =
        "You are reviewing a single rendered web page's HTML for a QA bug-crawler. Rule-based checks already "
            + "cover broken links/images, console errors, duplicate ids, and basic accessibility - do NOT repeat "
            + "those. Instead look for things only a reader would notice: leftover placeholder/lorem-ipsum text, "
            + "visible error messages or stack traces rendered into the page, obviously broken/garbled layout "
            + "markup, or mismatched/missing labels. Reply with ONLY a single JSON object, no prose outside it, no "
            + "markdown fence, shaped exactly as: {\"findings\": [{\"severity\": \"error|warning|info\", "
            + "\"description\": \"...\"}]} (findings may be an empty array if nothing is worth flagging). Do not "
            + "invent issues that aren't actually visible in the given HTML.";

    private AiPageReviewer() {
    }

    static void review(String url, String pageSource, PageResult result) {
        if (pageSource == null || pageSource.isBlank()) {
            return;
        }
        String prompt = "URL: " + url + "\n\nPage HTML (trimmed):\n"
            + AiFindings.truncate(pageSource, MAX_PAGE_SOURCE_CHARS);
        JsonNode json = AiFindings.askForJson(() -> OllamaClient.chat(SYSTEM_PROMPT, prompt),
            logger, "[SiteCrawler][AI]", "Page review call", url);
        if (json != null) {
            AiFindings.addFindings(json, "ai-review", result);
        }
    }
}
