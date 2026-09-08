package com.automation.core.crawler;

import java.util.ArrayList;
import java.util.List;

/** Everything {@link SiteCrawler} found on a single visited page. */
public final class PageResult {

    public final String url;
    public final int depth;
    public String title = "";
    public int statusCode = -1;
    public final List<CrawlIssue> issues = new ArrayList<>();

    public PageResult(String url, int depth) {
        this.url = url;
        this.depth = depth;
    }

    public void addIssue(CrawlIssue.Severity severity, String category, String description) {
        issues.add(new CrawlIssue(severity, category, description));
    }

    public long errorCount() {
        return issues.stream().filter(i -> i.severity == CrawlIssue.Severity.ERROR).count();
    }
}
