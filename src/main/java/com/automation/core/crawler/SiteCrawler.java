package com.automation.core.crawler;

import com.automation.core.ai.OllamaClient;
import com.automation.core.config.ConfigReader;
import com.deque.html.axecore.results.Results;
import com.deque.html.axecore.results.Rule;
import com.deque.html.axecore.selenium.AxeBuilder;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Crawls a site breadth-first from a start URL (same host only) using the
 * given WebDriver, and runs a set of automated bug checks on every page it
 * visits:
 *
 * <ul>
 *   <li>broken links/images — HTTP status via a lightweight HEAD/GET, not
 *       a full browser page load per link</li>
 *   <li>browser console errors (SEVERE-level entries)</li>
 *   <li>duplicate element ids</li>
 *   <li>empty/missing href and alt attributes</li>
 *   <li>mixed-content resources (http:// assets on an https:// page)</li>
 *   <li>accessibility violations, reusing the framework's existing
 *       axe-core wrapper ({@code AccessibilityUtils}'s underlying
 *       AxeBuilder, called directly here so a violation never fails the
 *       crawl — it's just another issue in the report)</li>
 * </ul>
 *
 * Optionally augments the rule-based findings with two independent text-LLM
 * passes per page (each off by default, each gated separately since they
 * serve different purposes and a person may want only one):
 * <ul>
 *   <li>{@link AiPageReviewer} ({@code crawler.ai.enabled}) — "flag anything
 *       you notice" — anomalies the fixed rule set doesn't cover, e.g.
 *       leftover placeholder text or visible error dumps rendered into the
 *       page.</li>
 *   <li>{@link AiChecklistReviewer} ({@code crawler.checklistFile}) — checks
 *       every page against a specific, human-supplied list of known or
 *       suspected bugs (one item per line in the given file; blank lines and
 *       lines starting with {@code #} are skipped). Independent of
 *       {@code crawler.ai.enabled}: pointing at a checklist file is itself
 *       the opt-in for this pass.</li>
 *   <li>{@link AiScreenshotReviewer} ({@code crawler.ai.vision.enabled}) —
 *       sends a screenshot of the page to a vision-capable LLM to catch
 *       visible layout/rendering bugs the two text-only passes above can't
 *       see (they only ever look at HTML, never at how it actually
 *       renders).</li>
 * </ul>
 * The two text passes share a single {@code driver.getPageSource()} call
 * per page rather than fetching it twice; the vision pass takes its own
 * screenshot only when enabled, since most runs won't want the extra
 * per-page screenshot/API-call cost.
 *
 * Invoked via {@link CrawlerCli} ({@code mvn exec:java@bug-crawler
 * -Pbug-crawler}) — see docs/AI_FEATURES.md for usage and config.
 */
public final class SiteCrawler {

    private static final Logger logger = LoggerFactory.getLogger(SiteCrawler.class);

    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final WebDriver driver;
    private final int maxPages;
    private final int maxDepth;
    private final boolean aiEnabled;
    private final boolean aiVisionEnabled;
    private final boolean a11yEnabled;
    private final List<String> checklist;

    public SiteCrawler(WebDriver driver) {
        this.driver = driver;
        this.maxPages = ConfigReader.getInt("crawler.maxPages", 50);
        this.maxDepth = ConfigReader.getInt("crawler.maxDepth", 3);
        this.aiEnabled = ConfigReader.getBoolean("crawler.ai.enabled", false) && OllamaClient.isConfigured();
        this.aiVisionEnabled = ConfigReader.getBoolean("crawler.ai.vision.enabled", false)
            && com.automation.core.ai.AiVisionClient.isConfigured();
        this.a11yEnabled = ConfigReader.getBoolean("crawler.a11y.enabled", true);
        this.checklist = loadChecklist(ConfigReader.get("crawler.checklistFile", ""));
    }

    /**
     * Loads {@code crawler.checklistFile} (one bug/behavior to look for per
     * line; blank lines and {@code #}-prefixed comment lines are skipped).
     * Returns an empty list — never null, never throws — when the property
     * is unset, the file doesn't exist, or it can't be read, so a bad path
     * just means "no checklist pass this run" rather than crashing the
     * whole crawl over an optional feature.
     */
    private static List<String> loadChecklist(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(path));
            List<String> items = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    items.add(trimmed);
                }
            }
            if (items.isEmpty()) {
                logger.warn("[SiteCrawler] crawler.checklistFile={} has no usable items "
                    + "(after skipping blank/comment lines) — checklist pass will be skipped.", path);
            } else {
                logger.info("[SiteCrawler] Loaded {} checklist item(s) from {}.", items.size(), path);
            }
            return items;
        } catch (Exception e) {
            logger.warn("[SiteCrawler] Could not read crawler.checklistFile={} ({}) — "
                + "checklist pass will be skipped for this run.", path, e.getMessage());
            return List.of();
        }
    }

    public CrawlReport crawl(String startUrl) {
        Instant startedAt = Instant.now();
        String startHost = hostOf(startUrl);

        Deque<PageToVisit> queue = new ArrayDeque<>();
        Set<String> queuedOrVisited = new HashSet<>();
        List<PageResult> results = new ArrayList<>();

        String normalizedStart = normalize(startUrl);
        queue.add(new PageToVisit(normalizedStart, 0));
        queuedOrVisited.add(normalizedStart);

        while (!queue.isEmpty() && results.size() < maxPages) {
            PageToVisit next = queue.poll();
            PageResult pageResult = visitPage(next.url, next.depth);
            results.add(pageResult);

            if (next.depth >= maxDepth) {
                continue;
            }
            for (String link : discoverSameHostLinks(startHost)) {
                String normalized = normalize(link);
                if (queuedOrVisited.add(normalized) && results.size() + queue.size() < maxPages) {
                    queue.add(new PageToVisit(normalized, next.depth + 1));
                }
            }
        }

        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();

        int totalIssues = results.stream().mapToInt(p -> p.issues.size()).sum();
        long totalErrors = results.stream()
            .flatMap(p -> p.issues.stream())
            .filter(i -> i.severity == CrawlIssue.Severity.ERROR)
            .count();
        logger.info("[SiteCrawler] Crawl finished: {} page(s) visited, {} issue(s) found ({} error(s)), "
            + "took {}ms", results.size(), totalIssues, totalErrors, durationMs);

        return new CrawlReport(startUrl, results, startedAt.toString(), finishedAt.toString(), durationMs);
    }

    private PageResult visitPage(String url, int depth) {
        PageResult result = new PageResult(url, depth);
        try {
            driver.get(url);
        } catch (Exception e) {
            result.addIssue(CrawlIssue.Severity.ERROR, "navigation", "Could not load page: " + e.getMessage());
            return result;
        }

        result.title = safeTitle();
        result.statusCode = checkLinkStatus(url).orElse(-1);

        checkConsoleErrors(result);
        checkLinksAndImages(result);
        checkDuplicateIds(result);
        checkMixedContent(result, url);
        if (a11yEnabled) {
            checkAccessibility(result);
        }
        boolean checklistEnabled = !checklist.isEmpty() && OllamaClient.isConfigured();
        if (aiEnabled || checklistEnabled) {
            // Fetched once and shared — each pass is an independent opt-in, but
            // there's no reason to hit driver.getPageSource() twice on the same
            // page just because both happen to be enabled together.
            String pageSource = safePageSource();
            if (aiEnabled) {
                AiPageReviewer.review(url, pageSource, result);
            }
            if (checklistEnabled) {
                AiChecklistReviewer.review(url, pageSource, checklist, result);
            }
        }
        if (aiVisionEnabled) {
            AiScreenshotReviewer.review(url, safeScreenshotBase64(), result);
        }
        return result;
    }

    /** Best-effort full-viewport screenshot for {@link AiScreenshotReviewer}; never throws. */
    private String safeScreenshotBase64() {
        try {
            return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        } catch (Exception e) {
            logger.debug("[SiteCrawler] Could not capture screenshot for AI visual review: {}", e.getMessage());
            return null;
        }
    }

    // ── Individual checks ───────────────────────────────────────────────

    private void checkConsoleErrors(PageResult result) {
        try {
            List<LogEntry> entries = driver.manage().logs().get(LogType.BROWSER).getAll();
            for (LogEntry entry : entries) {
                if ("SEVERE".equals(entry.getLevel().getName())) {
                    result.addIssue(CrawlIssue.Severity.ERROR, "console", truncate(entry.getMessage(), 300));
                }
            }
        } catch (Exception e) {
            // Firefox/geckodriver and some Grid nodes don't expose this log type — not itself a bug.
            logger.debug("[SiteCrawler] Console-log check unavailable: {}", e.getMessage());
        }
    }

    private void checkLinksAndImages(PageResult result) {
        List<WebElement> anchors = safeFindElements(By.tagName("a"));
        Set<String> checkedHrefs = new HashSet<>();
        for (WebElement a : anchors) {
            String href = safeAttr(a, "href");
            if (href == null || href.isBlank()) {
                result.addIssue(CrawlIssue.Severity.WARNING, "link", "Anchor tag with empty/missing href");
                continue;
            }
            if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tel:")
                || href.startsWith("#")) {
                continue;
            }
            if (!checkedHrefs.add(href)) {
                continue; // already checked this exact href on this page
            }
            int status = checkLinkStatus(href).orElse(0);
            if (status >= 400 || status == 0) {
                result.addIssue(CrawlIssue.Severity.ERROR, "broken-link", href + " -> HTTP " + status);
            }
        }

        List<WebElement> images = safeFindElements(By.tagName("img"));
        for (WebElement img : images) {
            String alt = safeAttr(img, "alt");
            if (alt == null) {
                result.addIssue(CrawlIssue.Severity.WARNING, "image-alt",
                    "<img> missing alt attribute (src=" + truncate(safeAttr(img, "src"), 120) + ")");
            }
            try {
                Object naturallyLoaded = ((JavascriptExecutor) driver)
                    .executeScript("return arguments[0].complete && arguments[0].naturalWidth > 0;", img);
                if (Boolean.FALSE.equals(naturallyLoaded)) {
                    result.addIssue(CrawlIssue.Severity.ERROR, "broken-image",
                        "Image failed to load: " + truncate(safeAttr(img, "src"), 120));
                }
            } catch (Exception ignored) {
                // Non-fatal — some elements/drivers don't support this script reliably.
            }
        }
    }

    private void checkDuplicateIds(PageResult result) {
        try {
            List<WebElement> withIds = driver.findElements(By.xpath("//*[@id]"));
            Map<String, Integer> counts = new HashMap<>();
            for (WebElement el : withIds) {
                String id = safeAttr(el, "id");
                if (id != null && !id.isBlank()) {
                    counts.merge(id, 1, Integer::sum);
                }
            }
            counts.forEach((id, count) -> {
                if (count > 1) {
                    result.addIssue(CrawlIssue.Severity.WARNING, "duplicate-id",
                        "id=\"" + id + "\" appears " + count + " times (ids must be unique)");
                }
            });
        } catch (Exception e) {
            logger.debug("[SiteCrawler] Duplicate-id check failed: {}", e.getMessage());
        }
    }

    private void checkMixedContent(PageResult result, String pageUrl) {
        if (!pageUrl.startsWith("https://")) {
            return;
        }
        try {
            List<WebElement> resources = new ArrayList<>();
            resources.addAll(driver.findElements(By.tagName("img")));
            resources.addAll(driver.findElements(By.tagName("script")));
            resources.addAll(driver.findElements(By.tagName("link")));
            for (WebElement el : resources) {
                String src = safeAttr(el, "src");
                if (src == null) {
                    src = safeAttr(el, "href");
                }
                if (src != null && src.startsWith("http://")) {
                    result.addIssue(CrawlIssue.Severity.WARNING, "mixed-content",
                        "Insecure resource on HTTPS page: " + truncate(src, 150));
                }
            }
        } catch (Exception e) {
            logger.debug("[SiteCrawler] Mixed-content check failed: {}", e.getMessage());
        }
    }

    private void checkAccessibility(PageResult result) {
        try {
            Results axeResults = new AxeBuilder().analyze(driver);
            for (Rule rule : axeResults.getViolations()) {
                String impact = rule.getImpact() == null ? "unknown" : rule.getImpact();
                CrawlIssue.Severity severity = ("critical".equalsIgnoreCase(impact)
                    || "serious".equalsIgnoreCase(impact))
                    ? CrawlIssue.Severity.ERROR : CrawlIssue.Severity.WARNING;
                result.addIssue(severity, "accessibility",
                    "[" + impact + "] " + rule.getId() + " — " + rule.getHelp()
                        + " (" + rule.getNodes().size() + " node(s))");
            }
        } catch (Exception e) {
            logger.debug("[SiteCrawler] Accessibility scan failed: {}", e.getMessage());
        }
    }

    // ── Link discovery / HTTP status ────────────────────────────────────

    private List<String> discoverSameHostLinks(String startHost) {
        List<String> links = new ArrayList<>();
        for (WebElement a : safeFindElements(By.tagName("a"))) {
            String href = safeAttr(a, "href");
            if (href == null || href.isBlank() || href.startsWith("javascript:") || href.startsWith("mailto:")
                || href.startsWith("tel:") || href.startsWith("#")) {
                continue;
            }
            String host = hostOf(href);
            if (host != null && host.equalsIgnoreCase(startHost)) {
                links.add(href);
            }
        }
        return links;
    }

    /** HEAD request (falls back to GET if the server rejects HEAD) — never loads the link in the browser. */
    private Optional<Integer> checkLinkStatus(String url) {
        try {
            HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            int status = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 405) {
                // Some servers reject HEAD outright — retry with GET before concluding anything.
                HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                status = httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
            return Optional.of(status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(0);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.of(0); // 0 = unreachable/timeout/malformed URL
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private List<WebElement> safeFindElements(By by) {
        try {
            return driver.findElements(by);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String safeTitle() {
        try {
            return driver.getTitle();
        } catch (Exception e) {
            return "";
        }
    }

    private String safePageSource() {
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeAttr(WebElement el, String name) {
        try {
            return el.getAttribute(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            return uri.getScheme() + "://" + uri.getHost() + path;
        } catch (Exception e) {
            return url;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final class PageToVisit {
        final String url;
        final int depth;

        PageToVisit(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}
