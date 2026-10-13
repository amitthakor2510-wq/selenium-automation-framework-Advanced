package com.automation.sites.listeners;

import com.automation.core.config.ConfigReader;
import com.automation.core.license.ClasspathResolver;
import com.automation.core.license.DependencyLicense;
import com.automation.core.license.LicenseScanCli;
import com.automation.core.license.LicenseScanResult;
import com.automation.core.license.LicenseStatus;
import com.automation.core.mail.EmailException;
import com.automation.core.mail.EmailService;
import com.automation.core.mail.EmailReportBuilder;
import com.automation.core.mail.EmailSettings;
import com.automation.core.mail.RunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestResult;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-of-run reporting hooks, both opt-in and both strictly best-effort (a problem here is logged and
 * never changes a test result or fails the run):
 *
 * <ol>
 *   <li><b>License compliance scan</b> - {@code license.scan.enabled=true}: scans the dependency jars on
 *       the test classpath against {@code license-policy.properties} and writes
 *       {@code target/license-report/}. A policy violation is logged as an ERROR here; use the
 *       {@code license-check} Maven profile when it must fail the build.</li>
 *   <li><b>E-mail report</b> - {@code email.enabled=true}: mails the pass/fail summary, the failed tests,
 *       the license summary and the report files (see the {@code email.*} keys in global.properties).</li>
 * </ol>
 *
 * <p>Registered through {@code META-INF/services/org.testng.ITestNGListener}, so it covers every suite
 * without touching suite XML files. It runs in {@code ISuiteListener.onFinish}, i.e. after
 * {@code TestListener.onFinish} has flushed the Extent report and written the zip.
 *
 * <p>One e-mail is sent per suite per JVM. A pipeline that runs many site/browser JVMs should enable
 * e-mail in only one place - the {@code email-report} profile ({@code EmailReportCli}) builds a single
 * consolidated mail from all the {@code testng-results.xml} files.
 */
public class PostRunReportListener implements ISuiteListener {

    private static final Logger logger = LoggerFactory.getLogger(PostRunReportListener.class);

    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();

    @Override
    public void onStart(ISuite suite) {
        startedAt.put(suite.getName(), Instant.now());
    }

    @Override
    public void onFinish(ISuite suite) {
        Instant started = startedAt.getOrDefault(suite.getName(), Instant.now());
        try {
            runLicenseScanIfEnabled();
        } catch (Throwable t) {
            logger.warn("[PostRunReport] License scan failed: {}", t.getMessage());
        }
        try {
            sendEmailIfEnabled(suite, started);
        } catch (Throwable t) {
            logger.warn("[PostRunReport] E-mail report failed: {}", t.getMessage());
        }
    }

    /**
     * Config value, or "" when absent. Falls back to plain {@code -D} system properties when the site
     * configuration cannot be loaded (e.g. an unregistered {@code -Dsite}) so reporting still works.
     */
    private static String config(String key) {
        try {
            return ConfigReader.get(key, "");
        } catch (RuntimeException e) {
            String sys = System.getProperty(key);
            return sys == null ? "" : sys;
        }
    }

    private static String configOr(String key, String dflt) {
        String v = config(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    private static void runLicenseScanIfEnabled() throws Exception {
        if (!Boolean.parseBoolean(config("license.scan.enabled"))) {
            return;
        }
        List<Path> jars = ClasspathResolver.resolve();
        if (jars.isEmpty()) {
            logger.warn("[PostRunReport] license.scan.enabled=true but no dependency jars were found on the classpath");
            return;
        }
        Path policy = Path.of(configOr("license.policy", "license-policy.properties"));
        Path out = Path.of(configOr("license.out", "target/license-report"));
        LicenseScanResult result = LicenseScanCli.scanAndWrite(policy, out, jars);
        logger.info("[PostRunReport] License scan: {} dependencies, {} need review, {} denied, {} without license. "
            + "Report: {}", result.dependencies().size(), result.count(LicenseStatus.REVIEW),
            result.count(LicenseStatus.DENIED),
            result.count(LicenseStatus.MISSING), out);
        for (DependencyLicense d : result.violations()) {
            logger.error("[PostRunReport] License policy violation: {} -> {}", d.coordinates(), d.licenseIds());
        }
    }

    private void sendEmailIfEnabled(ISuite suite, Instant started) {
        EmailSettings settings = EmailSettings.of(PostRunReportListener::config, System.getenv());
        if (!settings.enabled()) {
            return;
        }
        RunSummary summary = summarise(suite, started);
        try {
            EmailService.Outcome outcome = EmailService.deliver(settings, summary, started, System.getenv());
            logger.info("[PostRunReport] {}", outcome.message());
        } catch (EmailException e) {
            logger.warn("[PostRunReport] {}", e.getMessage());
        }
    }

    /** Counts exactly what TestNG reports, except that a SKIP which is only an earlier retry attempt is ignored. */
    static RunSummary summarise(ISuite suite, Instant started) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<RunSummary.FailedTest> failures = new ArrayList<>();
        for (ISuiteResult suiteResult : suite.getResults().values()) {
            ITestContext ctx = suiteResult.getTestContext();
            passed += ctx.getPassedTests().size();
            for (ITestResult r : ctx.getFailedTests().getAllResults()) {
                failed++;
                failures.add(toFailure(r));
            }
            for (ITestResult r : ctx.getFailedButWithinSuccessPercentageTests().getAllResults()) {
                failed++;
                failures.add(toFailure(r));
            }
            for (ITestResult r : ctx.getSkippedTests().getAllResults()) {
                if (!r.wasRetried()) {
                    skipped++;
                }
            }
        }
        String site = "";
        String browser = "";
        try {
            site = ConfigReader.getActiveSite();
            browser = ConfigReader.get("browser", "");
        } catch (RuntimeException e) {
            logger.debug("[PostRunReport] site/browser not available: {}", e.getMessage());
        }
        long durationMs = Duration.between(started, Instant.now()).toMillis();
        return new RunSummary(site, browser, suite.getName(), passed + failed + skipped, passed, failed, skipped,
            durationMs, started, failures, "", null);
    }

    private static RunSummary.FailedTest toFailure(ITestResult r) {
        Throwable t = r.getThrowable();
        String message = t == null ? "" : t.getClass().getSimpleName()
            + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return new RunSummary.FailedTest(r.getName(), r.getTestClass().getRealClass().getSimpleName(),
            EmailReportBuilder.sanitizeMessage(message));
    }
}
