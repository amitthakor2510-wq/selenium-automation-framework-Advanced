package com.automation.core.crawler;

import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import org.openqa.selenium.WebDriver;

import java.nio.file.Path;

/**
 * Command-line entry point for the AI bug crawler.
 *
 * <p>Invoked via {@code mvn exec:java@bug-crawler -Pbug-crawler
 * -Dcrawler.startUrl=https://example.com} (see the {@code bug-crawler}
 * profile in pom.xml), or directly:
 * {@code java -cp target/classes:... com.automation.core.crawler.CrawlerCli
 * https://example.com}.
 *
 * <p>Uses the same config layering as the rest of the framework
 * ({@code ConfigReader}, {@code -Dsite=...} for browser/headless config)
 * plus its own {@code crawler.*} keys (see global.properties) for
 * crawl depth/page limits and the optional AI review pass. See
 * docs/AI_FEATURES.md for full usage.
 */
public final class CrawlerCli {

    private CrawlerCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("[BugCrawler] Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String startUrl = (args.length > 0 && !args[0].isBlank()) ? args[0] : ConfigReader.get("crawler.startUrl", "");
        if (startUrl == null || startUrl.isBlank()) {
            throw new IllegalArgumentException("No start URL given — pass it as the first argument or set "
                + "-Dcrawler.startUrl=https://example.com");
        }

        WebDriver driver = DriverFactory.createDriver();
        try {
            SiteCrawler crawler = new SiteCrawler(driver);
            CrawlReport report = crawler.crawl(startUrl);

            Path outputDir = Path.of(ConfigReader.get("crawler.outputDir", "target/crawler"));
            new CrawlReportWriter(outputDir).write(report);

            System.out.println("[BugCrawler] " + report.pages.size() + " page(s) visited, "
                + report.totalIssues() + " issue(s) found (" + report.totalErrors() + " error(s)). Report: "
                + outputDir.resolve("crawl-report.txt"));
        } finally {
            driver.quit();
        }
    }
}
