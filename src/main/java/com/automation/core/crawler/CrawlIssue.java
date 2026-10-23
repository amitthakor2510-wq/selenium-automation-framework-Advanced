package com.automation.core.crawler;

/** One finding on one crawled page — see {@link SiteCrawler}. */
public final class CrawlIssue {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public final Severity severity;
    public final String category;
    public final String description;

    public CrawlIssue(Severity severity, String category, String description) {
        this.severity = severity;
        this.category = category;
        this.description = description;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + category + ": " + description;
    }
}
