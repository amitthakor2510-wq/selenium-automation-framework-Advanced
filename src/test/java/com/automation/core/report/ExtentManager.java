package com.automation.core.report;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.automation.core.config.ConfigReader;

public class ExtentManager {

    private static ExtentReports extent;

    public static synchronized ExtentReports getInstance() {

        if (extent == null) {

            String site = ConfigReader.getActiveSite();
            String reportPath = "target/extent-reports/" + site + "-report.html";

            ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
            spark.config().setTheme(Theme.STANDARD);
            spark.config().setDocumentTitle(site + " - Automation Report");
            spark.config().setReportName(site + " Regression/Smoke Report");

            extent = new ExtentReports();
            extent.attachReporter(spark);
            extent.setSystemInfo("Site", site);
            extent.setSystemInfo("Browser", ConfigReader.get("browser", "chrome"));
            extent.setSystemInfo("Headless", ConfigReader.get("headless", "false"));
        }

        return extent;
    }
}
