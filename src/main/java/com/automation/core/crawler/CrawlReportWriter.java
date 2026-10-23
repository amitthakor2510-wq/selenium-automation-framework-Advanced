package com.automation.core.crawler;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a {@link CrawlReport} to disk as JSON (machine-readable) and a plain-text summary (human-readable). */
public final class CrawlReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(CrawlReportWriter.class);

    private final Path outputDir;

    public CrawlReportWriter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void write(CrawlReport report) throws IOException {
        Files.createDirectories(outputDir);

        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        Path jsonPath = outputDir.resolve("crawl-report.json");
        mapper.writeValue(jsonPath.toFile(), report);

        Path textPath = outputDir.resolve("crawl-report.txt");
        Files.writeString(textPath, buildTextSummary(report));

        logger.info("[SiteCrawler] Report written: {} (JSON), {} (summary)", jsonPath, textPath);
    }

    private String buildTextSummary(CrawlReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bug Crawler Report\n");
        sb.append("Start URL: ").append(report.startUrl).append('\n');
        sb.append("Started: ").append(report.startedAt).append(", finished: ").append(report.finishedAt)
            .append(" (").append(report.durationMs).append("ms)\n");
        sb.append("Pages visited: ").append(report.pages.size()).append('\n');
        sb.append("Total issues: ").append(report.totalIssues())
            .append(" (").append(report.totalErrors()).append(" error(s))\n\n");

        for (PageResult page : report.pages) {
            // statusCode is -1 for non-HTTP crawls (e.g. the mobile app crawler, which has no
            // HTTP status per screen) — omit that segment entirely rather than printing "HTTP -1".
            String statusSegment = page.statusCode >= 0 ? ", HTTP " + page.statusCode : "";
            sb.append("== ").append(page.url).append(" (depth ").append(page.depth)
                .append(statusSegment).append(") ==\n");
            sb.append("Title: ").append(page.title).append('\n');
            if (page.issues.isEmpty()) {
                sb.append("  (no issues found)\n");
            } else {
                for (CrawlIssue issue : page.issues) {
                    sb.append("  ").append(issue).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
