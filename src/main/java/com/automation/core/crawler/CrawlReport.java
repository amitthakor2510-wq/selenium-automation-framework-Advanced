package com.automation.core.crawler;

import java.util.List;

/** Aggregate result of one {@link SiteCrawler#crawl(String)} run — see {@link CrawlReportWriter}. */
public final class CrawlReport {

    public final String startUrl;
    public final List<PageResult> pages;
    public final String startedAt;
    public final String finishedAt;
    public final long durationMs;

    public CrawlReport(String startUrl, List<PageResult> pages, String startedAt, String finishedAt,
                       long durationMs) {
        this.startUrl = startUrl;
        this.pages = pages;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMs = durationMs;
    }

    public int totalIssues() {
        return pages.stream().mapToInt(p -> p.issues.size()).sum();
    }

    public long totalErrors() {
        return pages.stream().flatMap(p -> p.issues.stream())
            .filter(i -> i.severity == CrawlIssue.Severity.ERROR)
            .count();
    }
}
