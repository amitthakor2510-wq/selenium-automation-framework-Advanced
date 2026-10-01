package com.automation.mobile.crawler;

import com.automation.core.ai.AiVisionClient;
import com.automation.core.ai.OllamaClient;
import com.automation.core.config.ConfigReader;
import com.automation.core.crawler.AiScreenshotReviewer;
import com.automation.core.crawler.CrawlIssue;
import com.automation.core.crawler.CrawlReport;
import com.automation.core.crawler.PageResult;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mobile counterpart to {@code com.automation.core.crawler.SiteCrawler} —
 * explores an already-launched app screen by screen (depth-first, tapping
 * clickable elements and navigating back rather than following URLs) and
 * runs a set of automated bug checks on every screen it visits:
 *
 * <ul>
 *   <li>crash/ANR dialogs ("has stopped", "isn't responding", etc.)</li>
 *   <li>leaving the app under test entirely (Android only — detected via
 *       {@code getCurrentPackage()})</li>
 *   <li>duplicate {@code resource-id}/accessibility-id values on the same
 *       screen</li>
 *   <li>image/icon controls with no accessible name (content-desc on
 *       Android, {@code name}/{@code label} on iOS) — the mobile
 *       equivalent of the web crawler's missing-{@code alt} check</li>
 *   <li>screens with suspiciously few visible elements (likely a blank/
 *       broken render)</li>
 * </ul>
 *
 * Optionally augments the rule-based findings with two independent AI
 * passes per screen (each off by default, each gated separately):
 * <ul>
 *   <li>{@link AiMobileScreenReviewer} ({@code crawler.mobile.ai.enabled})
 *       — text LLM skim of the screen's UI-hierarchy XML for anomalies the
 *       rule set doesn't cover.</li>
 *   <li>{@code AiScreenshotReviewer} ({@code crawler.mobile.ai.vision.enabled})
 *       — vision LLM look at an actual screenshot, catching visible
 *       layout/rendering bugs that XML alone can't show.</li>
 * </ul>
 *
 * <p><b>Safety — this taps real UI elements in a real app.</b> Unlike the
 * web crawler (which only ever issues read-only HTTP HEAD/GET requests to
 * discover links), this crawler physically taps buttons/links/cells to
 * discover new screens, which means it can trigger real actions — sending
 * a form, placing an order, logging out, deleting data — if the app
 * exposes them as reachable taps. Mitigations:
 * <ul>
 *   <li>{@code crawler.mobile.avoidTextContains} is a comma-separated,
 *       case-insensitive list of substrings (default covers common
 *       destructive/irreversible actions — delete, logout/sign out,
 *       uninstall, pay/purchase/buy, remove account, reset, submit,
 *       confirm) checked against each candidate element's visible text,
 *       content-desc/label, and resource-id before it is ever tapped.</li>
 *   <li>{@code crawler.mobile.maxScreens} / {@code crawler.mobile.maxDepth}
 *       / {@code crawler.mobile.maxElementsPerScreen} bound how much
 *       exploration happens at all.</li>
 * </ul>
 * This is a best-effort safety net, not a guarantee — review what a
 * screen's remaining (non-denylisted) taps can do before pointing this at
 * a production account/environment, the same way you would before letting
 * a new manual tester loose on it unsupervised.
 *
 * <p>Invoked via {@code MobileCrawlerCli} ({@code mvn exec:java@mobile-bug-crawler
 * -Pmobile-bug-crawler}) — see docs/AI_FEATURES.md for usage and config.
 */
public final class MobileAppCrawler {

    private static final Logger logger = LoggerFactory.getLogger(MobileAppCrawler.class);

    private static final List<String> CRASH_MARKERS = List.of(
        "has stopped", "keeps stopping", "isn't responding", "unfortunately",
        "process.crash", "app not responding");

    private final RemoteWebDriver driver;
    private final int maxScreens;
    private final int maxDepth;
    private final int maxElementsPerScreen;
    private final long tapSettleMillis;
    private final boolean aiEnabled;
    private final boolean aiVisionEnabled;
    private final List<String> avoidTextContains;
    private final String startPackage;

    public MobileAppCrawler(RemoteWebDriver driver) {
        this.driver = driver;
        this.maxScreens = ConfigReader.getInt("crawler.mobile.maxScreens", 30);
        this.maxDepth = ConfigReader.getInt("crawler.mobile.maxDepth", 4);
        this.maxElementsPerScreen = ConfigReader.getInt("crawler.mobile.maxElementsPerScreen", 8);
        this.tapSettleMillis = ConfigReader.getInt("crawler.mobile.tapSettleMillis", 800);
        this.aiEnabled = ConfigReader.getBoolean("crawler.mobile.ai.enabled", false)
            && OllamaClient.isConfigured();
        this.aiVisionEnabled = ConfigReader.getBoolean("crawler.mobile.ai.vision.enabled", false)
            && AiVisionClient.isConfigured();
        this.avoidTextContains = parseAvoidList(ConfigReader.get("crawler.mobile.avoidTextContains",
            "delete,logout,log out,sign out,uninstall,pay,purchase,buy,remove account,reset,submit,confirm"));
        this.startPackage = safeCurrentPackage();
    }

    private static List<String> parseAvoidList(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Crawls from whatever screen the app is currently showing (Appium
     * sessions are created already pointed at the target app — see
     * {@code AppiumDriverFactory} — there is no "start URL" equivalent).
     */
    public CrawlReport crawl() {
        Instant startedAt = Instant.now();
        List<PageResult> results = new ArrayList<>();
        Set<String> visitedSignatures = new HashSet<>();

        exploreDepthFirst(0, results, visitedSignatures);

        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        int totalIssues = results.stream().mapToInt(p -> p.issues.size()).sum();
        long totalErrors = results.stream().flatMap(p -> p.issues.stream())
            .filter(i -> i.severity == CrawlIssue.Severity.ERROR).count();
        logger.info("[MobileAppCrawler] Crawl finished: {} screen(s) visited, {} issue(s) found ({} error(s)), "
            + "took {}ms", results.size(), totalIssues, totalErrors, durationMs);

        return new CrawlReport(startPackage == null ? "(mobile app)" : startPackage, results,
            startedAt.toString(), finishedAt.toString(), durationMs);
    }

    /**
     * Visits the current screen, records it, then tries each not-yet-avoided
     * clickable candidate in turn: tap, recurse one level deeper, navigate
     * back. Backtracking after every child keeps the driver on a screen
     * this method already knows how to recognize, rather than compounding
     * navigation state across siblings.
     */
    private void exploreDepthFirst(int depth, List<PageResult> results, Set<String> visitedSignatures) {
        if (results.size() >= maxScreens) {
            return;
        }
        String signature = screenSignature();
        boolean firstVisit = visitedSignatures.add(signature);
        PageResult result = visitScreen(signature, depth);
        results.add(result);

        if (!firstVisit || depth >= maxDepth || results.size() >= maxScreens) {
            return;
        }

        List<WebElement> candidates = clickableCandidates();
        int tried = 0;
        for (WebElement candidate : candidates) {
            if (tried >= maxElementsPerScreen || results.size() >= maxScreens) {
                break;
            }
            if (isAvoided(candidate)) {
                continue;
            }
            tried++;
            String beforeSignature = signature;
            boolean tapped = safeTap(candidate);
            if (!tapped) {
                continue;
            }
            sleep(tapSettleMillis);

            if (leftAppUnderTest()) {
                PageResult leftAppResult = new PageResult("left-app-from:" + beforeSignature, depth + 1);
                leftAppResult.addIssue(CrawlIssue.Severity.WARNING, "left-app",
                    "Tapping an element navigated outside the app under test (package changed) — "
                        + "not explored further.");
                results.add(leftAppResult);
                navigateBack();
                sleep(tapSettleMillis);
                continue;
            }

            String afterSignature = screenSignature();
            if (afterSignature.equals(beforeSignature)) {
                // Tap didn't change the screen (no-op control, or a transient overlay that
                // already closed) — nothing new to explore from here.
                continue;
            }
            exploreDepthFirst(depth + 1, results, visitedSignatures);
            navigateBack();
            sleep(tapSettleMillis);
        }
    }

    private PageResult visitScreen(String signature, int depth) {
        PageResult result = new PageResult(signature, depth);
        result.title = signature;

        if (isCrashOrAnrScreen()) {
            result.addIssue(CrawlIssue.Severity.ERROR, "app-crash",
                "Screen looks like a crash/ANR dialog (matched a known crash-dialog phrase).");
            return result; // Nothing else useful to check on a crash dialog.
        }

        checkDuplicateAccessibilityIds(result);
        checkMissingAccessibleNames(result);
        checkBlankScreen(result);

        if (aiEnabled) {
            AiMobileScreenReviewer.review(signature, safePageSource(), result);
        }
        if (aiVisionEnabled) {
            AiScreenshotReviewer.review(signature, safeScreenshotBase64(), result);
        }
        return result;
    }

    // ── Individual checks ───────────────────────────────────────────────

    private boolean isCrashOrAnrScreen() {
        String source = safePageSource();
        if (source == null || source.isEmpty()) {
            return false;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        return CRASH_MARKERS.stream().anyMatch(lower::contains);
    }

    private void checkDuplicateAccessibilityIds(PageResult result) {
        try {
            List<WebElement> all = driver.findElements(By.xpath("//*"));
            Map<String, Integer> counts = new HashMap<>();
            for (WebElement el : all) {
                String id = firstNonBlank(safeAttr(el, "resource-id"), safeAttr(el, "name"));
                if (id != null && !id.isBlank()) {
                    counts.merge(id, 1, Integer::sum);
                }
            }
            counts.forEach((id, count) -> {
                if (count > 1) {
                    result.addIssue(CrawlIssue.Severity.WARNING, "duplicate-id",
                        "id=\"" + id + "\" appears " + count + " times on this screen (ids should be unique)");
                }
            });
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Duplicate-id check failed: {}", e.getMessage());
        }
    }

    /** Mobile equivalent of the web crawler's missing-{@code alt} check. */
    private void checkMissingAccessibleNames(PageResult result) {
        try {
            List<WebElement> imageLikes = new ArrayList<>();
            imageLikes.addAll(safeFindElements(By.className("android.widget.ImageView")));
            imageLikes.addAll(safeFindElements(By.className("android.widget.ImageButton")));
            imageLikes.addAll(safeFindElements(By.className("XCUIElementTypeImage")));
            imageLikes.addAll(safeFindElements(By.className("XCUIElementTypeButton")));
            for (WebElement el : imageLikes) {
                String contentDesc = firstNonBlank(safeAttr(el, "content-desc"), safeAttr(el, "name"),
                    safeAttr(el, "label"));
                String text = safeAttr(el, "text");
                if ((contentDesc == null || contentDesc.isBlank()) && (text == null || text.isBlank())) {
                    result.addIssue(CrawlIssue.Severity.WARNING, "missing-accessible-name",
                        "Image/icon control with no content-desc/name/label and no text "
                            + "(class=" + truncate(safeAttr(el, "class"), 60) + ") — screen readers "
                            + "have nothing to announce for it.");
                }
            }
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Missing-accessible-name check failed: {}", e.getMessage());
        }
    }

    private void checkBlankScreen(PageResult result) {
        try {
            int visibleCount = safeFindElements(By.xpath("//*[@text!='' or @content-desc!='' or @label!='']")).size();
            if (visibleCount == 0) {
                result.addIssue(CrawlIssue.Severity.WARNING, "blank-screen",
                    "No elements with visible text/content-desc/label found on this screen — "
                        + "possible blank or broken render.");
            }
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Blank-screen check failed: {}", e.getMessage());
        }
    }

    // ── Screen discovery / signature / navigation ───────────────────────

    private List<WebElement> clickableCandidates() {
        List<WebElement> found = new ArrayList<>();
        found.addAll(safeFindElements(By.xpath("//*[@clickable='true']")));
        // iOS/XCUITest elements don't expose a "clickable" attribute the same way — fall back to
        // the interactive element types that commonly act as navigation triggers there.
        found.addAll(safeFindElements(By.xpath(
            "//*[@type='XCUIElementTypeButton' or @type='XCUIElementTypeLink' or @type='XCUIElementTypeCell']")));
        return found;
    }

    private boolean isAvoided(WebElement el) {
        String haystack = String.join(" | ",
            nullToEmpty(safeAttr(el, "text")),
            nullToEmpty(safeAttr(el, "content-desc")),
            nullToEmpty(safeAttr(el, "name")),
            nullToEmpty(safeAttr(el, "label")),
            nullToEmpty(safeAttr(el, "resource-id"))).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return false;
        }
        for (String avoided : avoidTextContains) {
            if (haystack.contains(avoided)) {
                return true;
            }
        }
        return false;
    }

    private boolean safeTap(WebElement el) {
        try {
            if (!el.isDisplayed() || !el.isEnabled()) {
                return false;
            }
            el.click();
            return true;
        } catch (StaleElementReferenceException e) {
            return false; // Screen already moved on since candidates were collected — skip it.
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Tap failed, skipping this candidate: {}", e.getMessage());
            return false;
        }
    }

    private void navigateBack() {
        try {
            driver.navigate().back();
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] navigate().back() failed: {}", e.getMessage());
        }
    }

    private boolean leftAppUnderTest() {
        if (startPackage == null) {
            return false; // iOS, or package unavailable — this guard only works on Android.
        }
        String current = safeCurrentPackage();
        return current != null && !current.isBlank() && !current.equals(startPackage);
    }

    /**
     * Identifies the current screen well enough to detect "have I been here
     * before" and "did this tap actually change anything". On Android this
     * is the foreground activity name (cheap and precise); elsewhere it
     * falls back to a structural fingerprint of the UI-hierarchy XML
     * (element types + resource-ids, not dynamic text/values), which is
     * best-effort — two visually distinct screens sharing the same control
     * types/ids could theoretically collide, but this only affects how
     * much the crawler explores, never the correctness of issues it finds
     * on a screen it does visit.
     */
    private String screenSignature() {
        if (driver instanceof AndroidDriver androidDriver) {
            try {
                String activity = androidDriver.currentActivity();
                if (activity != null && !activity.isBlank()) {
                    return activity;
                }
            } catch (Exception ignored) {
                // Fall through to the structural fingerprint below.
            }
        }
        return "xml-fingerprint:" + structuralFingerprint(safePageSource());
    }

    private String structuralFingerprint(String pageSourceXml) {
        if (pageSourceXml == null || pageSourceXml.isEmpty()) {
            return "empty";
        }
        StringBuilder skeleton = new StringBuilder();
        for (WebElement el : safeFindElements(By.xpath("//*"))) {
            skeleton.append(nullToEmpty(safeAttr(el, "class")))
                .append('#').append(nullToEmpty(safeAttr(el, "resource-id"))).append(';');
        }
        return Integer.toHexString(skeleton.toString().hashCode());
    }

    private String safeCurrentPackage() {
        try {
            if (driver instanceof AndroidDriver androidDriver) {
                return androidDriver.getCurrentPackage();
            }
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Could not read current package: {}", e.getMessage());
        }
        return null;
    }

    private String safePageSource() {
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            return "";
        }
    }

    private String safeScreenshotBase64() {
        try {
            return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        } catch (Exception e) {
            logger.debug("[MobileAppCrawler] Could not capture screenshot for AI visual review: {}", e.getMessage());
            return null;
        }
    }

    private List<WebElement> safeFindElements(By by) {
        try {
            return driver.findElements(by);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String safeAttr(WebElement el, String name) {
        try {
            return el.getAttribute(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
