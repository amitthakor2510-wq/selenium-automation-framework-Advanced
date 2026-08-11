package com.automation.core.selfhealing;

import com.automation.core.config.ConfigReader;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drop-in replacement for {@code wait.until(ExpectedConditions.visibility/
 * elementToBeClickable(locator))} that heals instead of failing outright
 * when a locator that used to work suddenly doesn't.
 *
 * How it works:
 *   1. Try the given locator normally, with the caller's own wait/timeout.
 *   2. On success, snapshot the element's identifying attributes (tag, id,
 *      name, classes, text, a handful of common attributes, parent tag)
 *      as an {@link ElementFingerprint} and remember it against a key
 *      derived from the current page + the locator itself.
 *   3. If the locator times out, look up the fingerprint saved the last
 *      time this exact locator succeeded (possibly in an earlier run —
 *      see {@link LocatorRepository}), scan the live DOM for elements of
 *      the same tag, and score each one against that fingerprint.
 *   4. If the best match clears the confidence threshold, use it, log a
 *      warning (so the drift doesn't go unnoticed even though the test
 *      passes), and record a {@link HealingEvent} for the end-of-run
 *      report. Otherwise the original timeout is rethrown — healing only
 *      ever recovers a run, never silently invents an answer that wasn't
 *      there.
 *
 * There is nothing to heal against the very first time a locator is ever
 * used (no fingerprint recorded yet) — that case just behaves exactly like
 * a normal wait.until(...) and fails normally, as it should.
 *
 * This is the general, automatic counterpart to {@link SmartLocator}: that
 * class is for locators a developer has *already* seen break and wants an
 * explicit, hand-picked fallback for; this engine is what runs everywhere
 * else, healing locators nobody has had to think about yet. SmartLocator
 * also delegates to this engine as its own last resort once every explicit
 * fallback it was given has failed.
 */
public final class SelfHealingEngine {

    private static final Logger logger = LoggerFactory.getLogger(SelfHealingEngine.class);

    /** Common, relatively stable attributes worth capturing when present. */
    private static final String[] TRACKED_ATTRIBUTES = {
        "type", "placeholder", "aria-label", "role", "href", "title", "data-testid"
    };

    private static final int MAX_CANDIDATES_SCANNED = 300;
    private static final int MAX_TEXT_LENGTH = 100;

    private SelfHealingEngine() {
    }

    /** Finds a VISIBLE element, healing by similarity if the locator has drifted. */
    public static WebElement find(WebDriver driver, WebDriverWait wait, By locator) {
        return resolve(driver, wait, locator, ExpectedConditions::visibilityOfElementLocated, false);
    }

    /** Finds a CLICKABLE element, healing by similarity if the locator has drifted. */
    public static WebElement findClickable(WebDriver driver, WebDriverWait wait, By locator) {
        return resolve(driver, wait, locator, ExpectedConditions::elementToBeClickable, true);
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    private static WebElement resolve(WebDriver driver, WebDriverWait wait, By locator,
                                      java.util.function.Function<By, ExpectedCondition<WebElement>> condition,
                                      boolean requireClickable) {
        String key = elementKey(driver, locator);
        try {
            WebElement element = wait.until(condition.apply(locator));
            captureAndStore(key, element);
            return element;
        } catch (TimeoutException | NoSuchElementException primaryFailure) {
            if (!ConfigReader.getBoolean("self-healing.enabled", true)) {
                throw primaryFailure;
            }
            WebElement healed = attemptHeal(driver, key, locator, requireClickable);
            if (healed != null) {
                return healed;
            }
            throw primaryFailure;
        }
    }

    private static WebElement attemptHeal(WebDriver driver, String key, By locator, boolean requireClickable) {
        ElementFingerprint baseline = LocatorRepository.get(key);
        if (baseline == null || baseline.tag == null || baseline.tag.isEmpty()) {
            // Never seen this element succeed before (in this run or a prior
            // one) — nothing to heal against, so don't guess.
            return null;
        }

        double threshold = parseThreshold();
        ScoredCandidate best = findBestMatch(driver, baseline, requireClickable);
        if (best == null || best.score < threshold) {
            logger.warn("[SelfHealing] '" + key + "' broke and no candidate matched closely enough"
                + " (best score " + (best == null ? "n/a" : String.format(Locale.ROOT, "%.2f", best.score))
                + ", threshold " + threshold + "). Falling back to the original failure.");
            return null;
        }

        logger.warn("[SelfHealing] '" + key + "' — original locator " + locator
            + " no longer matches; healed onto " + describe(best.element)
            + " (confidence " + String.format(Locale.ROOT, "%.2f", best.score) + ").");

        LocatorRepository.recordHeal(new HealingEvent(key, locator.toString(), describe(best.element), best.score));
        captureAndStore(key, best.element);
        return best.element;
    }

    private static double parseThreshold() {
        String raw = ConfigReader.get("self-healing.threshold", "0.55");
        try {
            double t = Double.parseDouble(raw.trim());
            return Math.max(0.0, Math.min(1.0, t));
        } catch (NumberFormatException e) {
            return 0.55;
        }
    }

    // ── Fingerprint capture ──────────────────────────────────────────────────

    private static void captureAndStore(String key, WebElement element) {
        try {
            LocatorRepository.put(key, buildFingerprint(element));
        } catch (Exception e) {
            // Fingerprinting is a best-effort side-channel — never let it
            // break the actual test interaction that just succeeded.
            logger.debug("[SelfHealing] Could not fingerprint element for '" + key + "': " + e.getMessage());
        }
    }

    private static ElementFingerprint buildFingerprint(WebElement element) {
        ElementFingerprint fp = new ElementFingerprint();
        fp.tag = safe(element.getTagName());
        fp.id = attr(element, "id");
        fp.name = attr(element, "name");
        fp.classes = classTokens(attr(element, "class"));
        fp.text = truncate(safeText(element));
        for (String attrName : TRACKED_ATTRIBUTES) {
            String value = attr(element, attrName);
            if (value != null && !value.isEmpty()) {
                fp.attributes.put(attrName, value);
            }
        }
        fp.parentTag = parentTag(element);
        return fp;
    }

    private static String parentTag(WebElement element) {
        try {
            return safe(element.findElement(By.xpath("..")).getTagName());
        } catch (Exception e) {
            return null;
        }
    }

    private static String attr(WebElement element, String name) {
        try {
            return element.getAttribute(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeText(WebElement element) {
        try {
            return element.getText();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= MAX_TEXT_LENGTH ? trimmed : trimmed.substring(0, MAX_TEXT_LENGTH);
    }

    private static List<String> classTokens(String classAttr) {
        List<String> tokens = new ArrayList<>();
        if (classAttr == null || classAttr.isBlank()) {
            return tokens;
        }
        for (String token : classAttr.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // ── Candidate search + scoring ───────────────────────────────────────────

    /**
     * Scans all elements matching baseline.tag and returns the highest-scoring
     * one that is actually usable — i.e. when {@code requireClickable} is set,
     * candidates that are displayed but disabled are skipped during scanning
     * itself, not just checked against the single overall top-scorer.
     * <p>
     * BUG FIX: this used to pick the single best-scoring candidate across ALL
     * displayed elements first, then reject it afterward in attemptHeal() if
     * requireClickable was set and that one candidate happened to be disabled
     * — even when a second-best (but still perfectly good and clickable)
     * candidate existed. That threw away a real healing opportunity: a click
     * request would give up and rethrow the original failure while a usable
     * match sat unscored a few candidates further down the DOM. Filtering
     * eligibility up front means the best-scoring candidate returned here is
     * always one the caller can actually use.
     */
    private static ScoredCandidate findBestMatch(WebDriver driver, ElementFingerprint baseline,
                                                 boolean requireClickable) {
        List<WebElement> candidates;
        try {
            candidates = driver.findElements(By.tagName(baseline.tag));
        } catch (Exception e) {
            return null;
        }

        WebElement bestElement = null;
        double bestScore = 0.0;
        int scanned = 0;

        for (WebElement candidate : candidates) {
            if (scanned++ >= MAX_CANDIDATES_SCANNED) {
                break;
            }
            try {
                if (!candidate.isDisplayed()) {
                    continue;
                }
                if (requireClickable && !candidate.isEnabled()) {
                    continue;
                }
                double s = score(baseline, buildFingerprint(candidate));
                if (s > bestScore) {
                    bestScore = s;
                    bestElement = candidate;
                }
            } catch (StaleElementReferenceException ignored) {
                // Element vanished mid-scan (re-render) — skip it.
            }
        }

        return bestElement == null ? null : new ScoredCandidate(bestElement, bestScore);
    }

    /**
     * Similarity score in [0, 1] between a stored fingerprint and a live
     * candidate. Weighted toward the signals least likely to change for
     * cosmetic/unrelated reasons (id, name) and least toward the ones most
     * likely to (visible text, which can be localized or dynamic).
     */
    private static double score(ElementFingerprint base, ElementFingerprint candidate) {
        if (base.tag != null && candidate.tag != null && !base.tag.equalsIgnoreCase(candidate.tag)) {
            return 0.0;
        }

        double total = 0.0;

        if (nonEmpty(base.id) && base.id.equals(candidate.id)) {
            total += 0.30;
        }
        if (nonEmpty(base.name) && base.name.equals(candidate.name)) {
            total += 0.20;
        }
        total += 0.20 * jaccard(base.classes, candidate.classes);
        total += 0.15 * textSimilarity(base.text, candidate.text);
        total += 0.10 * attributeOverlap(base.attributes, candidate.attributes);
        if (nonEmpty(base.parentTag) && base.parentTag.equalsIgnoreCase(candidate.parentTag)) {
            total += 0.05;
        }

        return Math.min(total, 1.0);
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static double jaccard(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> setA = new java.util.HashSet<>(a);
        java.util.Set<String> setB = new java.util.HashSet<>(b);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        if (union.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        return (double) intersection.size() / union.size();
    }

    private static double textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int distance = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 0.0 : 1.0 - ((double) distance / maxLen);
    }

    private static double attributeOverlap(Map<String, String> base, Map<String, String> candidate) {
        if (base == null || base.isEmpty()) {
            return 0.0;
        }
        int matches = 0;
        for (Map.Entry<String, String> entry : base.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(candidate.get(entry.getKey()))) {
                matches++;
            }
        }
        return (double) matches / base.size();
    }

    /** Classic dynamic-programming edit distance; strings here are capped short (<=100 chars) by truncate(). */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private static String describe(WebElement element) {
        String id = attr(element, "id");
        if (nonEmpty(id)) {
            return "#" + id;
        }
        String name = attr(element, "name");
        if (nonEmpty(name)) {
            return safe(element.getTagName()) + "[name='" + name + "']";
        }
        List<String> classes = classTokens(attr(element, "class"));
        if (!classes.isEmpty()) {
            return safe(element.getTagName()) + "." + classes.get(0);
        }
        return safe(element.getTagName()) + " (text: '" + truncate(safeText(element)) + "')";
    }

    /**
     * Keys a fingerprint by page + locator rather than locator alone, so the
     * same CSS selector reused on two different pages doesn't cross-pollute.
     * Query string/fragment are stripped since they usually reflect
     * transient state (IDs, filters) rather than a different page layout.
     */
    static String elementKey(WebDriver driver, By locator) {
        String pagePath;
        try {
            URI uri = URI.create(driver.getCurrentUrl());
            pagePath = uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Exception e) {
            pagePath = "unknown-page";
        }
        return pagePath + " :: " + locator;
    }

    private static final class ScoredCandidate {
        final WebElement element;
        final double score;

        ScoredCandidate(WebElement element, double score) {
            this.element = element;
            this.score = score;
        }
    }
}
