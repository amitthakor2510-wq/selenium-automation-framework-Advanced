package com.automation.core.crawler;

import com.automation.core.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Targeted counterpart to {@link AiPageReviewer}: instead of "flag anything
 * you notice", this checks a page against a specific, user-supplied list of
 * bugs/behaviors to look for (see {@code crawler.checklistFile} — one item
 * per line, e.g. "the footer copyright year is stuck on 2023", "the cart
 * badge doesn't update after a quick-add"). Every item is checked on every
 * crawled page; only items the model is confident it can actually see
 * evidence of in the page's HTML are reported — a "not present on this
 * page" or "can't tell from HTML alone" result is silently dropped rather
 * than padding the report with negative results for every item on every
 * page.
 *
 * Same safety posture as the rest of this framework's AI features:
 * report-only, best-effort, never fails the crawl. A stale/wrong finding
 * is possible (it's still a language model reading HTML, not executing
 * your app) — treat results as leads to verify, not confirmed bugs.
 */
final class AiChecklistReviewer {

    private static final Logger logger = LoggerFactory.getLogger(AiChecklistReviewer.class);
    private static final int MAX_PAGE_SOURCE_CHARS = 6000;

    private AiChecklistReviewer() {
    }

    static void review(String url, String pageSource, List<String> checklist, PageResult result) {
        if (pageSource == null || pageSource.isBlank() || checklist == null || checklist.isEmpty()) {
            return;
        }
        try {
            String reply = OllamaClient.chat(buildSystemPrompt(checklist), buildUserPrompt(url, pageSource));
            JsonNode json = OllamaClient.extractJsonObject(reply);
            if (json == null) {
                logger.debug("[SiteCrawler][AI] Checklist reply had no parseable JSON for {} — skipping.", url);
                return;
            }
            JsonNode results = json.get("results");
            if (results == null || !results.isArray()) {
                return;
            }
            for (JsonNode entry : results) {
                if (!entry.hasNonNull("item") || !entry.hasNonNull("status")) {
                    continue;
                }
                int itemIndex = entry.get("item").asInt(-1);
                if (itemIndex < 0 || itemIndex >= checklist.size()) {
                    continue;
                }
                String status = entry.get("status").asText("").trim().toLowerCase(java.util.Locale.ROOT);
                if (!"present".equals(status)) {
                    // "absent" and "uncertain" are both non-findings for report purposes —
                    // only a confident positive match is worth a human's attention here.
                    continue;
                }
                String evidence = entry.hasNonNull("evidence") ? entry.get("evidence").asText() : "";
                String description = checklist.get(itemIndex)
                    + (evidence.isBlank() ? "" : " — evidence: " + evidence);
                result.addIssue(CrawlIssue.Severity.WARNING, "checklist", description);
            }
        } catch (Exception e) {
            logger.debug("[SiteCrawler][AI] Checklist review call failed for {} ({}) — continuing without it.",
                url, e.getMessage());
        }
    }

    private static String buildSystemPrompt(List<String> checklist) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are checking a single rendered web page's HTML against a specific checklist of known or "
            + "suspected bugs, provided by a human QA engineer. For EACH numbered checklist item below, decide "
            + "whether the given page HTML shows clear evidence the item is present ('present'), clear evidence "
            + "it is NOT an issue on this page ('absent'), or the HTML doesn't give you enough to tell either "
            + "way ('uncertain' — e.g. the item describes behavior/interaction the static HTML alone can't show). "
            + "Be conservative: only mark 'present' when you can point to specific evidence in the HTML given. "
            + "Reply with ONLY a single JSON object, no prose outside it, no markdown fence, shaped exactly as: "
            + "{\"results\": [{\"item\": <checklist item number, 0-based>, \"status\": \"present|absent|uncertain\", "
            + "\"evidence\": \"short quote or description of what you saw, only if status is present\"}]} "
            + "— include one entry per checklist item, every time.\n\nChecklist:\n");
        for (int i = 0; i < checklist.size(); i++) {
            sb.append(i).append(": ").append(checklist.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String buildUserPrompt(String url, String pageSource) {
        return "URL: " + url + "\n\nPage HTML (trimmed):\n" + truncate(pageSource, MAX_PAGE_SOURCE_CHARS);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
    }
}
