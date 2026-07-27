package com.automation.core.report;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.automation.core.config.ConfigReader;

public class ExtentManager {

    private static ExtentReports extent;

    public static synchronized ExtentReports getInstance() {

        if (extent == null) {
            ConfigReader.init();
            String site = ConfigReader.getActiveSite();
            // Jenkins runs one "mvn test" per site in a loop without an
            // intermediate "mvn clean" (see Jenkinsfile "Run Tests Per Site"
            // stage), so a fixed file name here means each site's run just
            // overwrites the previous site's report. Name it per site instead;
            // publishHTML/archiveArtifacts already glob target/extent-reports/*.html.
            String reportPath = "target/extent-reports/" + site + "-index.html";

            ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
            spark.config().setTheme(Theme.STANDARD);
            spark.config().setDocumentTitle(site + " - Automation Report");
            spark.config().setReportName(site + " Regression/Smoke Report");
            spark.config().setTimeStampFormat("MMM dd, yyyy HH:mm:ss");
            // Inlines the report's CSS/JS instead of loading them from a CDN — CI artifacts
            // and downloaded zips get opened on machines with no internet access, and the
            // dashboard/timeline tabs render blank without this if the CDN isn't reachable.
            spark.config().enableOfflineMode(true);

            extent = new ExtentReports();
            extent.attachReporter(spark);
            extent.setSystemInfo("Site", site);
            extent.setSystemInfo("Browser", ConfigReader.get("browser", "chrome"));
            extent.setSystemInfo("Headless", ConfigReader.get("headless", "false"));
            extent.setSystemInfo("OS",       System.getProperty("os.name"));
            extent.setSystemInfo("Java",     System.getProperty("java.version"));
            extent.setSystemInfo("Retry",    ConfigReader.get("retry.count", "0"));
        }

        return extent;
    }

    /** Call this before starting a new test run in the same JVM. */
    public static synchronized void reset() {
        if (extent != null) {
            extent.flush();
        }
        extent = null;
    }

    private static final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ExtentManager.class.getName());
}
