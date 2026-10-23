package com.automation.core.selfhealing;

import com.automation.core.ai.OllamaClient;
import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * SelfHealingEngine's stage-3, opt-in, last-resort healing tier: when
 * neither DOM attribute/text scoring nor the visual screenshot-hash
 * fallback can find a confident match, describe the baseline element and
 * a pool of live candidates to a text LLM (see
 * {@link com.automation.core.ai.OllamaClient}) and ask it to pick the
 * closest match by index.
 *
 * Deliberately index-based rather than asking the model to invent a CSS
 * selector or XPath: a hallucinated selector might not even parse, or
 * might silently match the wrong element on a page the model never
 * actually saw structurally (it only sees the trimmed text description
 * below). An index into a list Java itself already resolved via
 * {@code driver.findElements(...)} can only ever point at a real, live
 * element — the model's job is purely to rank real candidates, never to
 * conjure one out of nothing.
 *
 * Off by default ({@code self-healing.ai.enabled=false}) — it's the last
 * resort after DOM + visual both miss, and costs a network round-trip to
 * the AI provider configured for {@code OllamaClient}.
 */
final class AiLocatorHealer {

    private static final Logger logger = LoggerFactory.getLogger(AiLocatorHealer.class);

    /** Hard cap on candidates described to the model — keeps the prompt small and the index space sane. */
    private static final int MAX_CANDIDATES = 25;
    private static final int MAX_ELEMENT_TEXT_CHARS = 80;

    private static final String SYSTEM_PROMPT =
        "You are helping a Selenium test-automation framework recover a broken element locator. "
            + "You are given a description of the element that used to be found (its tag, id, name, classes, "
            + "visible text, and a few attributes) and a numbered list of currently-visible candidate elements on "
            + "the same page. Pick the candidate that is most likely the SAME logical element after a markup "
            + "change (renamed id/class, restructured wrapper, rewritten text, etc.), or -1 if none plausibly "
            + "match. Reply with ONLY a single JSON object, no prose outside it, no markdown fence, shaped exactly "
            + "as: {\"index\": <int, the candidate's number, or -1>, \"confidence\": <float 0.0-1.0>, "
            + "\"reason\": \"one short sentence\"}. Be conservative: a wrong guess is worse than admitting no "
            + "match — only give confidence above 0.6 when you are genuinely confident.";

    private AiLocatorHealer() {
    }

    static boolean isEnabled() {
        return ConfigReader.getBoolean("self-healing.ai.enabled", false) && OllamaClient.isConfigured();
    }

    /**
     * @return the chosen element plus the model's self-reported confidence,
     *         only when that confidence clears self-healing.ai.confidence
     *         AND the element is still displayed/usable at the moment of
     *         return (re-checked here — candidates can go stale between
     *         the scan and the AI round-trip completing). Null otherwise;
     *         every failure mode (disabled, empty pool, unparsable reply,
     *         low confidence, network error) degrades to null so the
     *         caller just falls through to the original failure.
     */
    static AiHealResult attemptHeal(ElementFingerprint baseline, boolean requireClickable, List<WebElement> pool) {
        if (!isEnabled() || pool == null || pool.isEmpty()) {
            return null;
        }

        List<WebElement> trimmedPool = pool.size() > MAX_CANDIDATES ? pool.subList(0, MAX_CANDIDATES) : pool;

        try {
            String prompt = buildPrompt(baseline, requireClickable, trimmedPool);
            String reply = OllamaClient.chat(SYSTEM_PROMPT, prompt);
            JsonNode json = OllamaClient.extractJsonObject(reply);
            if (json == null) {
                logger.debug("[SelfHealing][AI] Reply had no parseable JSON — skipping AI stage.");
                return null;
            }

            JsonNode indexNode = json.get("index");
            JsonNode confidenceNode = json.get("confidence");
            if (indexNode == null || confidenceNode == null) {
                logger.debug("[SelfHealing][AI] Reply JSON missing index/confidence — skipping AI stage.");
                return null;
            }
            int index = indexNode.asInt(-1);
            double confidence = confidenceNode.asDouble(0.0);
            if (index < 0 || index >= trimmedPool.size()) {
                // -1 (or any out-of-range value) means "the model found no match" — a valid, honest answer.
                return null;
            }

            double threshold = parseConfidenceThreshold();
            if (confidence < threshold) {
                logger.debug("[SelfHealing][AI] Best AI candidate (index {}) confidence {} below threshold {} "
                    + "— skipping.", index, confidence, threshold);
                return null;
            }

            WebElement candidate = trimmedPool.get(index);
            if (!candidate.isDisplayed() || (requireClickable && !candidate.isEnabled())) {
                logger.debug("[SelfHealing][AI] Chosen candidate is no longer displayed/enabled — skipping.");
                return null;
            }

            String reason = json.hasNonNull("reason") ? json.get("reason").asText() : "";
            logger.info("[SelfHealing][AI] Model picked candidate #{} with confidence {} — {}",
                index, String.format(Locale.ROOT, "%.2f", confidence),
                reason.isBlank() ? "(no reason given)" : reason);
            return new AiHealResult(candidate, confidence);
        } catch (StaleElementReferenceException staleEx) {
            return null;
        } catch (Exception e) {
            logger.warn("[SelfHealing][AI] AI healing call failed ({}) — falling back to the original failure.",
                e.getMessage());
            return null;
        }
    }

    private static String buildPrompt(ElementFingerprint baseline, boolean requireClickable,
                                      List<WebElement> pool) {
        StringBuilder sb = new StringBuilder();
        sb.append("Element that broke (tag=").append(baseline.tag)
            .append(", id=").append(nullToDash(baseline.id))
            .append(", name=").append(nullToDash(baseline.name))
            .append(", classes=").append(baseline.classes)
            .append(", text=\"").append(nullToDash(baseline.text)).append('"')
            .append(", attributes=").append(baseline.attributes)
            .append(", requireClickable=").append(requireClickable)
            .append(")\n\nCandidates:\n");

        for (int i = 0; i < pool.size(); i++) {
            sb.append(i).append(": ").append(describe(pool.get(i))).append('\n');
        }
        return sb.toString();
    }

    private static String describe(WebElement el) {
        try {
            return "tag=" + el.getTagName()
                + ", id=" + nullToDash(attr(el, "id"))
                + ", class=" + nullToDash(attr(el, "class"))
                + ", role=" + nullToDash(attr(el, "role"))
                + ", aria-label=" + nullToDash(attr(el, "aria-label"))
                + ", text=\"" + truncate(safeText(el)) + "\"";
        } catch (Exception e) {
            return "(stale/unreadable)";
        }
    }

    private static String attr(WebElement el, String name) {
        try {
            return el.getAttribute(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeText(WebElement el) {
        try {
            return el.getText();
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= MAX_ELEMENT_TEXT_CHARS ? trimmed : trimmed.substring(0, MAX_ELEMENT_TEXT_CHARS);
    }

    private static String nullToDash(Object o) {
        return o == null || o.toString().isEmpty() ? "-" : o.toString();
    }

    private static double parseConfidenceThreshold() {
        String raw = ConfigReader.get("self-healing.ai.confidence", "0.6");
        try {
            double t = Double.parseDouble(raw.trim());
            return Math.max(0.0, Math.min(1.0, t));
        } catch (NumberFormatException e) {
            return 0.6;
        }
    }

    /** Result of a successful AI heal — the chosen element plus the model's own confidence. */
    static final class AiHealResult {
        final WebElement element;
        final double confidence;

        AiHealResult(WebElement element, double confidence) {
            this.element = element;
            this.confidence = confidence;
        }
    }
}
