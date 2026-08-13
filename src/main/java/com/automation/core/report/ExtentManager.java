package com.automation.core.report;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.automation.core.config.ConfigReader;

import java.util.concurrent.ConcurrentHashMap;

public class ExtentManager {

    // Keyed by site+browser+suite instead of a single shared static instance
    // — a single shared instance meant two sites (or two threads, or two
    // suite types in the same JVM) would interleave results into whichever
    // report got created first.
    private static final ConcurrentHashMap<String, ExtentReports> INSTANCES = new ConcurrentHashMap<>();

    // BUG FIX: the running TestNG suite's own <suite name="..."> (e.g.
    // "DemoQA Regression Suite", "DemoQA Accessibility Suite") is the one
    // reliable, always-available signal for "which test type is this" —
    // available for free from ITestContext, requiring no extra -D flag
    // anyone has to remember to pass (unlike site/browser, which are real
    // config values with their own meaning). Previously there was no
    // suite-type signal here at all: two different suite types run
    // back-to-back for the same site+browser (e.g. the Nightly Extra
    // Coverage stage's accessibility/visual runs, or simply running smoke
    // then regression locally without `mvn clean` in between) silently
    // overwrote each other's report under the identical filename — the
    // Jenkinsfile used to paper over this with `mv target/extent-reports/
    // <site>-index.html target/extent-reports/<site>-<suite>-index.html`
    // AFTER each run, but that rename target didn't even match this
    // class's actual (browser-inclusive) filename pattern any more, so it
    // silently no-op'd. Set once per test, from BaseTest.setUp()/
    // MobileBaseTest.setUp() (NOT from TestListener.onStart(ITestContext)
    // — that <test>-level callback never fires for a listener registered
    // via class-level @Listeners, the same dead-code trap documented on
    // BaseTest.setUp()'s AllureEnvironmentWriter.writeOnce() call; this
    // class used to rely on onStart() the same way and silently never got
    // set at all). TestNG creates exactly one JVM per suite XML in this
    // framework's Maven-driven model (see the Jenkinsfile's "one mvn test
    // per site/suite" comments), so a plain static field (not ThreadLocal)
    // is correct here even under parallel="classes"/"methods" — every
    // thread in this JVM is running the same single suite.
    private static volatile String activeSuiteName;

    /** Called from BaseTest.setUp()/MobileBaseTest.setUp() (NOT
     *  TestListener.onStart(ITestContext) — see the field comment above
     *  for why that never fires) before any test in this suite creates its
     *  ExtentTest — must run first so create() below can read it. Cheap
     *  to call on every test (plain field assignment), so no guard needed. */
    public static void setActiveSuiteName(String suiteName) {
        activeSuiteName = suiteName;
    }

    private static String suiteSlug() {
        String raw = activeSuiteName;
        if (raw == null || raw.isBlank()) {
            return "suite";
        }
        // "DemoQA Regression Suite" -> "demoqa-regression-suite"; keeps the
        // slug filesystem/URL-safe and stable regardless of how a suite's
        // display name is capitalized/spaced in its XML.
        String slug = raw.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "suite" : slug;
    }

    public static synchronized ExtentReports getInstance() {
        ConfigReader.init();
        String site = ConfigReader.getActiveSite();
        String browser = ConfigReader.get("browser", "chrome");
        String key = "mobile".equals(site)
            ? site + "|" + suiteSlug()
            : site + "|" + browser + "|" + suiteSlug();
        return INSTANCES.computeIfAbsent(key, k -> create(site, browser));
    }

    private static ExtentReports create(String site, String browser) {
        String suiteSlug = suiteSlug();
        // Nested by site, then browser (or "mobile" for the app), then
        // suite/test-type — this hierarchy is what makes the report set
        // separable browser-wise, website/app-wise, and test-type-wise
        // without anyone needing to remember a -D flag: it falls out of
        // config that's already there (site/browser) plus the suite name
        // TestNG already knows. archiveArtifacts/publishHTML in the
        // Jenkinsfile already glob the whole target/extent-reports tree, so
        // the deeper nesting needs no CI-side change to keep working; the
        // Jenkinsfile's separated per-report publishHTML calls (see
        // Jenkinsfile) rely on exactly this structure to label each report.
        String reportPath = "mobile".equals(site)
            ? "target/extent-reports/" + site + "/" + suiteSlug + "/index.html"
            : "target/extent-reports/" + site + "/" + browser + "/" + suiteSlug + "/index.html";

        ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
        spark.config().setTheme(Theme.STANDARD);
        spark.config().setDocumentTitle(site + " (" + browser + ") — " + activeSuiteName);
        spark.config().setReportName((activeSuiteName != null ? activeSuiteName : site + " Report"));
        spark.config().setTimeStampFormat("MMM dd, yyyy HH:mm:ss");
        // Inlines the report's CSS/JS instead of loading them from a CDN — CI artifacts
        // and downloaded zips get opened on machines with no internet access, and the
        // dashboard/timeline tabs render blank without this if the CDN isn't reachable.
        spark.config().enableOfflineMode(true);

        ExtentReports extent = new ExtentReports();
        extent.attachReporter(spark);
        extent.setSystemInfo("Site", site);
        extent.setSystemInfo("Browser", browser);
        extent.setSystemInfo("Suite", activeSuiteName != null ? activeSuiteName : "(unknown)");
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
        activeSuiteName = null;
    }
}
