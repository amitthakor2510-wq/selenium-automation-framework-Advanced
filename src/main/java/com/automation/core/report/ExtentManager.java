package com.automation.core.report;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.automation.core.config.ConfigReader;

import java.util.concurrent.ConcurrentHashMap;

public class ExtentManager {

    // Keyed by site instead of a single shared static instance — a single
    // shared instance meant two sites (or two threads) running in the same
    // JVM would interleave results into whichever report got created first.
    private static final ConcurrentHashMap<String, ExtentReports> INSTANCES = new ConcurrentHashMap<>();

    public static synchronized ExtentReports getInstance() {
        ConfigReader.init();
        String site = ConfigReader.getActiveSite();
        return INSTANCES.computeIfAbsent(site, ExtentManager::create);
    }

    private static ExtentReports create(String site) {
        // Jenkins runs one "mvn test" per site in a loop without an
        // intermediate "mvn clean" (see Jenkinsfile "Run Tests Per Site"
        // stage), so a fixed file name here means each site's run just
        // overwrites the previous site's report. Name it per site instead;
        // publishHTML/archiveArtifacts already glob target/extent-reports/*.html.
        //
        // Also keyed by browser, not just site: github-ci.yml's `test` job
        // matrixes every site across 4 browsers (chrome/firefox/edge/safari),
        // each as its own isolated-runner job that uploads its own
        // `test-results-<site>-<browser>-<run_id>` artifact. The allure-report
        // job then downloads every one of those with `merge-multiple: true`
        // into the SAME target/ directory to build the combined GitHub Pages
        // site. A site-only filename (e.g. "demoqa-index.html") was identical
        // across all 4 of that site's browser legs, so download-artifact's
        // merge silently overwrote 3 of every 4 browsers' Extent reports with
        // whichever one happened to be merged last — no error, just missing
        // data, which is why "the Extent report" could look incomplete or
        // simply not show up for the browser someone was checking. Including
        // the browser keeps every leg's file distinct all the way through
        // that merge. mobile has no browser matrix, so it keeps the plain
        // site-only name.
        String browser = ConfigReader.get("browser", "chrome");
        String reportPath = "mobile".equals(site)
            ? "target/extent-reports/" + site + "-index.html"
            : "target/extent-reports/" + site + "-" + browser + "-index.html";

        ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
        spark.config().setTheme(Theme.STANDARD);
        spark.config().setDocumentTitle(site + " - Automation Report");
        spark.config().setReportName(site + " Regression/Smoke Report");
        spark.config().setTimeStampFormat("MMM dd, yyyy HH:mm:ss");
        // Inlines the report's CSS/JS instead of loading them from a CDN — CI artifacts
        // and downloaded zips get opened on machines with no internet access, and the
        // dashboard/timeline tabs render blank without this if the CDN isn't reachable.
        spark.config().enableOfflineMode(true);

        ExtentReports extent = new ExtentReports();
        extent.attachReporter(spark);
        extent.setSystemInfo("Site", site);
        extent.setSystemInfo("Browser", ConfigReader.get("browser", "chrome"));
        extent.setSystemInfo("Headless", ConfigReader.get("headless", "false"));
        extent.setSystemInfo("OS", System.getProperty("os.name"));
        extent.setSystemInfo("Java", System.getProperty("java.version"));
        extent.setSystemInfo("Retry", ConfigReader.get("retry.count", "0"));
        return extent;
    }

    /** Call this before starting a new test run in the same JVM. */
    public static synchronized void reset() {
        INSTANCES.values().forEach(ExtentReports::flush);
        INSTANCES.clear();
    }
}
