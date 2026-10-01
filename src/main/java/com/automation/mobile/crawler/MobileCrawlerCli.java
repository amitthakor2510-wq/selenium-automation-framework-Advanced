package com.automation.mobile.crawler;

import com.automation.core.config.ConfigReader;
import com.automation.core.crawler.CrawlReport;
import com.automation.core.crawler.CrawlReportWriter;
import com.automation.mobile.core.AppiumDriverFactory;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.nio.file.Path;

/**
 * Command-line entry point for the mobile app bug crawler.
 *
 * <p>Invoked via {@code mvn exec:java@mobile-bug-crawler -Pmobile-bug-crawler}
 * (see the {@code mobile-bug-crawler} profile in pom.xml), or directly:
 * {@code java -cp target/classes:... com.automation.mobile.crawler.MobileCrawlerCli}.
 *
 * <p>Unlike the web crawler, there is no start-URL argument — Appium
 * launches (or attaches to) the target app as part of driver creation (see
 * {@code mobile.app.path} / {@code mobile.app.package} + {@code
 * mobile.app.activity} in {@code AppiumDriverFactory}), so the crawl simply
 * begins on whatever screen the app opens to. Uses the same config
 * layering as the rest of the framework ({@code ConfigReader}) plus its
 * own {@code crawler.mobile.*} keys (see global.properties) for
 * exploration limits, the tap-avoid list, and the two optional AI review
 * passes. See docs/AI_FEATURES.md for full usage and safety notes before
 * running this against a real device/account.
 */
public final class MobileCrawlerCli {

    private MobileCrawlerCli() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            System.err.println("[MobileBugCrawler] Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void run() throws Exception {
        RemoteWebDriver driver = AppiumDriverFactory.createDriver();
        try {
            MobileAppCrawler crawler = new MobileAppCrawler(driver);
            CrawlReport report = crawler.crawl();

            Path outputDir = Path.of(ConfigReader.get("crawler.mobile.outputDir", "target/mobile-crawler"));
            new CrawlReportWriter(outputDir).write(report);

            System.out.println("[MobileBugCrawler] " + report.pages.size() + " screen(s) visited, "
                + report.totalIssues() + " issue(s) found (" + report.totalErrors() + " error(s)). Report: "
                + outputDir.resolve("crawl-report.txt"));
        } finally {
            driver.quit();
        }
    }
}
