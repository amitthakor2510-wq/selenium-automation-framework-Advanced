package com.automation.sites.listeners;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.report.AllureEnvironmentWriter;
import com.automation.core.report.ExtentManager;
import com.automation.core.utils.FailureDiagnostics;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import com.automation.core.utils.VideoRecorder;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import io.qameta.allure.Allure;
import org.openqa.selenium.WebDriver;
import org.slf4j.MDC;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Implements BOTH ITestListener (Extent Reports bookkeeping) and
 * IInvokedMethodListener (all Allure attachments/labels).
 *
 * WHY TWO INTERFACES INSTEAD OF ONE:
 * AllureTestNg (Allure's own auto-registered listener) is itself a plain
 * ITestListener — its onTestStart() opens the "current Allure test case" and
 * its onTestFailure()/onTestSuccess() closes it. Our old code also drove the
 * Allure attachments from ITestListener.onTestFailure()/onTestStart(), which
 * put us in a race against AllureTestNg: TestNG does NOT guarantee relative
 * firing order between two listeners that implement the *same* interface
 * (open upstream issue: testng-team/testng#2089). Whichever one happened to
 * run first on a given JVM/run "won" — when AllureTestNg's onTestFailure()
 * ran before ours, it had already closed the test case, so our
 * Allure.addAttachment() call for the failure screenshot silently no-opped
 * (isAllureTestCaseOpen() caught it and skipped quietly instead of logging
 * loudly, which is exactly why this looked like "sometimes the screenshot
 * just doesn't show up" rather than a hard, reproducible failure).
 *
 * IInvokedMethodListener is a DIFFERENT interface with a guaranteed position:
 * TestNG's Invoker fires beforeInvocation()/afterInvocation() from inside its
 * own invokeMethod() call, strictly before it runs any ITestListener
 * notifications for that same result. So afterInvocation() is guaranteed to
 * execute before AllureTestNg's onTestFailure() ever gets a chance to close
 * the case — no ordering race, independent of how @Listeners/suite-XML/
 * ServiceLoader happened to register things.
 */
public class TestListener implements ITestListener, IInvokedMethodListener {

    // NOT cached in a static final field on purpose: ExtentManager.reset()
    // (called from onFinish() below) clears ExtentManager's internal
    // per-site map so a second site run in the same JVM gets a fresh
    // ExtentReports instance. A static final field here would have grabbed
    // the instance once at class-load time and kept writing to the first
    // site's already-flushed report forever, silently defeating the whole
    // point of ExtentManager.reset(). getInstance() is cheap (a
    // ConcurrentHashMap lookup), so fetching it fresh each time costs nothing.
    private static ExtentReports extent() {
        return ExtentManager.getInstance();
    }

    private static final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    // Video recording lifecycle: started in beforeInvocation, stopped in
    // afterInvocation (both IInvokedMethodListener, so still driver-alive —
    // same timing reasoning as the screenshot/diagnostics attachments
    // below). The resulting file is stashed in videoForExtent so the later
    // ITestListener callbacks (onTestSuccess/Failure/Skipped), which own
    // the Extent `test` ThreadLocal, can link it into the Extent report too
    // without VideoRecorder needing to know anything about Extent.
    private static final ThreadLocal<VideoRecorder> videoRecorder = new ThreadLocal<>();
    private static final ThreadLocal<File> videoForExtent = new ThreadLocal<>();

    // ── Helper: grab driver from test instance ───────────────────────────────

    private WebDriver getDriver(ITestResult result) {
        Object instance = result.getInstance();
        if (instance instanceof DriverProvider) {
            return ((DriverProvider) instance).getDriver();
        }
        return null;
    }

    /** Maps TestNG groups (smoke/regression/etc.) onto an Allure severity label. */
    private void tagSeverity(ITestResult result) {
        String[] groups = result.getMethod().getGroups();
        String severity = Arrays.asList(groups).contains("smoke") ? "critical" : "normal";
        Allure.label("severity", severity);
    }

    /** TestNG reuses a single IRetryAnalyzer instance across every attempt of a given test
     *  method, so its count reflects the true number of retries once the method has finished. */
    private int retryAttemptsSoFar(ITestResult result) {
        Object analyzer = result.getMethod().getRetryAnalyzer(result);
        if (analyzer instanceof RetryAnalyzer retryAnalyzer) {
            return retryAnalyzer.getCount();
        }
        return 0;
    }

    /**
     * Belt-and-braces only at this point — with the Allure calls now made
     * from IInvokedMethodListener the case should always be open, but this
     * still guards against the case-of-cases (e.g. AllureTestNg missing from
     * the classpath entirely) where nothing was ever opened at all.
     */
    private boolean isAllureTestCaseOpen() {
        return Allure.getLifecycle().getCurrentTestCaseOrStep().isPresent();
    }

    // ── IInvokedMethodListener: everything Allure-related lives here ────────────
    // Guaranteed to run before TestNG fires any ITestListener notification
    // (including AllureTestNg's) for the same result — see class javadoc.

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        if (!method.isTestMethod()) {
            return;
        }
        // Tags every SLF4J log line this thread emits for the rest of this
        // test — page objects, DriverFactory, self-healing, etc. — with
        // which test produced it. Essential once parallel="classes" is
        // actually running multiple test classes concurrently (see the
        // testng-suites/*.xml files): without this, target/logs/<site>.log
        // interleaves lines from 2-3 threads with zero way to tell which
        // test each one belongs to. Set here (IInvokedMethodListener) rather
        // than ITestListener.onTestStart() for the same ordering-guarantee
        // reason the rest of this class's javadoc explains — beforeInvocation
        // is guaranteed to fire before the actual @Test method body runs,
        // with no cross-listener race. NOTE: this fires after @BeforeMethod
        // (BaseTest.setUp() / MobileBaseTest.setUp()) already completed —
        // driver-creation log lines from setUp() itself are tagged
        // separately, by setUp() itself setting the same MDC key via
        // TestNG's @BeforeMethod(Method) dependency injection (see
        // BaseTest.setUp), since this listener can't reach back into a
        // @BeforeMethod that already finished. This call re-sets the
        // identical value for the @Test body itself — redundant with that
        // one, but harmless, and keeps this listener correct on its own
        // even for a test class whose setUp() doesn't tag MDC itself.
        MDC.put("test", result.getTestClass().getRealClass().getSimpleName()
            + "#" + result.getMethod().getMethodName());

        String site = ConfigReader.get("site", ConfigReader.getActiveSite());
        String browser = ConfigReader.get("browser", "chrome");

        if (isAllureTestCaseOpen()) {
            tagSeverity(result);
            Allure.parameter("Site", site);
            Allure.parameter("Browser", browser);

            // Real Allure LABELS (not just parameters) for site/browser/category —
            // Scripts/generate_segmented_reports.py reads these to build the
            // separate browser-wise/site(app)-wise/category-wise report sets;
            // "mobile" is deliberately labeled "platform" instead of "browser"
            // since it isn't one. Test-type (suite) splitting needs no new label
            // here — allure-testng already emits "parentSuite" from the running
            // TestNG <suite> automatically, which the same script reuses.
            Allure.label("site", site);
            if ("mobile".equals(site)) {
                Allure.label("platform", "android");
            } else {
                Allure.label("browser", browser);
            }
            for (String group : result.getMethod().getGroups()) {
                Allure.label("tag", group);
            }
        }

        // Web tests only (BaseTest) — Appium/mobile has its own native
        // device-recording API and isn't wired up here, see VideoRecorder's
        // class javadoc and global.properties' video.enabled comment.
        if (result.getInstance() instanceof com.automation.sites.core.BaseTest) {
            VideoRecorder recorder = new VideoRecorder();
            recorder.start(result.getMethod().getMethodName());
            videoRecorder.set(recorder);
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (!method.isTestMethod()) {
            return;
        }
        // NOTE: does NOT MDC.remove("test") here. afterInvocation() fires
        // strictly before @AfterMethod (BaseTest.tearDown() /
        // MobileBaseTest.tearDown()) runs — clearing the tag at this point
        // used to leave driver.quit() logging in tearDown() untagged (empty
        // "[]" MDC), the exact same class of gap setUp() had before it got
        // its own MDC.put() (see BaseTest.setUp's comment). The tag is
        // removed instead in tearDown()'s own finally block, once teardown
        // logging has actually happened, which is also guaranteed to run
        // (alwaysRun = true) so this thread's next test still starts clean.
        afterTestInvocation(method, result);
    }

    private void afterTestInvocation(IInvokedMethod method, ITestResult result) {
        WebDriver driver = getDriver(result);
        int retryAttempts = retryAttemptsSoFar(result);

        // Stop the recorder (if one was started) before branching on status,
        // so every branch below — including the early SUCCESS return — goes
        // through the same keep-or-discard decision. stop() itself is a
        // no-op returning null when start() never actually began recording
        // (video.enabled=false, headless, or a startup error).
        VideoRecorder recorder = videoRecorder.get();
        videoRecorder.remove();
        File video = recorder != null ? recorder.stop() : null;

        if (result.getStatus() == ITestResult.SUCCESS) {
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
            if (isAllureTestCaseOpen()) {
                if (bytes.length > 0) {
                    Allure.addAttachment(
                        "Pass Screenshot — " + result.getMethod().getMethodName(),
                        "image/png",
                        new ByteArrayInputStream(bytes),
                        "png"
                    );
                }
                if (retryAttempts > 0) {
                    // Only passed after failing at least once first — flag it so the
                    // Categories/overview widgets separate "flaky" from a clean first-try pass.
                    Allure.label("flaky", "true");
                    Allure.parameter("Retry Attempts", String.valueOf(retryAttempts));
                }
            }
            keepOrDiscardVideo(video, result, "Pass");
            return;
        }

        if (result.getStatus() == ITestResult.FAILURE) {
            if (!isAllureTestCaseOpen()) {
                keepOrDiscardVideo(video, result, "Failure");
                return;
            }
            if (retryAttempts > 0) {
                Allure.parameter("Retry Attempts", String.valueOf(retryAttempts));
            }
            attachFailureDiagnostics(result, driver, "Failure");
            keepOrDiscardVideo(video, result, "Failure");
            return;
        }

        // BUG FIX: skipped results previously got NO Allure attachments at
        // all — this branch didn't exist. That mattered far more than it
        // looks like, because most "skipped" results here aren't a plain
        // @Test(enabled=false)/dependency-skip: TestNG marks every
        // RETRIED-BUT-STILL-FAILED intermediate attempt as SKIP, not
        // FAILURE, whenever RetryAnalyzer.retry() returns true for that
        // attempt (only the LAST attempt keeps the true FAILURE/SUCCESS
        // status) — see RetryAnalyzer.getCount()'s own javadoc. So every
        // test that failed once and then passed/failed on retry was
        // silently losing the screenshot/page-source/console-log evidence
        // for every attempt except the final one, which is exactly the
        // "screenshot doesn't attach on skipped tests" gap. A genuine
        // SkipException (e.g. BrokenLinksImagesTest's external-CDN
        // fallback) benefits the same way. Driver may legitimately be null
        // here (a test skipped before setUp() ever created one, e.g. a
        // TestNG dependency-on-a-failed-method skip) — the diagnostics
        // helper below already no-ops cleanly on a null driver, same as
        // the failure path.
        if (result.getStatus() == ITestResult.SKIP) {
            if (!isAllureTestCaseOpen()) {
                keepOrDiscardVideo(video, result, "Skip");
                return;
            }
            if (retryAttempts > 0) {
                Allure.parameter("Retry Attempts", String.valueOf(retryAttempts));
            }
            attachFailureDiagnostics(result, driver, "Skip");
            keepOrDiscardVideo(video, result, "Skip");
        }
    }

    /** Shared by the FAILURE and SKIP branches above — same three diagnostics,
     *  just a different attachment-name prefix so the two are distinguishable
     *  in the Allure UI. */
    private void attachFailureDiagnostics(ITestResult result, WebDriver driver, String label) {
        byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
        if (bytes.length > 0) {
            Allure.addAttachment(
                label + " Screenshot — " + result.getMethod().getMethodName(),
                "image/png",
                new ByteArrayInputStream(bytes),
                "png"
            );
        }

        String pageSource = FailureDiagnostics.capturePageSource(driver);
        if (!pageSource.isEmpty()) {
            Allure.addAttachment(
                "Page Source — " + result.getMethod().getMethodName(),
                "text/html",
                new ByteArrayInputStream(pageSource.getBytes(StandardCharsets.UTF_8)),
                "html"
            );
        }

        String consoleLogs = FailureDiagnostics.captureBrowserConsoleLogs(driver);
        if (!consoleLogs.isEmpty()) {
            Allure.addAttachment(
                "Browser Console Logs — " + result.getMethod().getMethodName(),
                "text/plain",
                new ByteArrayInputStream(consoleLogs.getBytes(StandardCharsets.UTF_8)),
                "log"
            );
        }

        if (driver != null) {
            Allure.parameter(label + " URL", safeCurrentUrl(driver));
        }
    }

    /** Decides whether a just-stopped recording is worth keeping: always for
     *  Failure/Skip (that's the whole point — video.keep.on.pass only governs
     *  the Pass case), attaches it to Allure (if a case is open) either way,
     *  and stashes it in videoForExtent for the ITestListener callback that's
     *  about to run to link into the Extent report. Discarded files are
     *  deleted immediately rather than left for a human/CI-cleanup step,
     *  matching this framework's existing "don't let evidence nobody asked
     *  for pile up on disk" stance (see DriverFactory's temp profile-dir
     *  cleanup, VisualRegressionUtils' namespacing, etc.). AVI/TSCC isn't a
     *  browser-playable codec, so this is offered as a download/attachment,
     *  not an inline <video> preview, in both Allure and Extent. */
    private void keepOrDiscardVideo(File video, ITestResult result, String label) {
        if (video == null || !video.exists()) {
            videoForExtent.set(null);
            return;
        }
        boolean keep = !"Pass".equals(label) || ConfigReader.getBoolean("video.keep.on.pass", false);
        if (!keep) {
            VideoRecorder.discard(video);
            videoForExtent.set(null);
            return;
        }
        if (isAllureTestCaseOpen()) {
            try (FileInputStream in = new FileInputStream(video)) {
                Allure.addAttachment(
                    label + " Recording — " + result.getMethod().getMethodName(),
                    "video/avi",
                    in,
                    "avi"
                );
            } catch (Exception e) {
                // Never let a video-attachment failure look like the test itself failed
                // differently than it did — same defensive stance as ScreenshotUtil.
            }
        }
        videoForExtent.set(video);
    }

    private String safeCurrentUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    // ── ITestListener: Extent Reports bookkeeping only ───────────────────────
    // No Allure.* calls below — see class javadoc for why they moved above.

    @Override
    public void onStart(ITestContext context) {
        // Written once per JVM: populates the Allure report's "Environment"
        // widget and "Categories" tab before any test results land in
        // target/allure-results.
        AllureEnvironmentWriter.writeOnce();
    }

    @Override
    public void onTestStart(ITestResult result) {
        String testName    = result.getMethod().getMethodName();
        String description = result.getMethod().getDescription();

        ExtentTest extentTest;
        synchronized (TestListener.class) {
            extentTest = extent().createTest(
                testName,
                (description != null && !description.isEmpty()) ? description : ""
            );
        }

        // Categories/author/device populate the Dashboard tab's filter chips and the
        // Tests-by-category donut chart — without them every test shows up "uncategorized"
        // and the dashboard has nothing to slice by.
        String[] groups = result.getMethod().getGroups();
        if (groups.length > 0) {
            extentTest.assignCategory(groups);
        }
        extentTest.assignDevice(ConfigReader.get("browser", "chrome"));
        extentTest.assignAuthor(ConfigReader.get("site", ConfigReader.getActiveSite()));

        test.set(extentTest);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        try {
            if (test.get() != null) {
                test.get().pass("Test Passed");
                linkVideoIntoExtent();
            }
            HumanActions.postTestPause();
        } finally {
            // Clean up this thread's ThreadLocal entry right here, rather than
            // relying solely on onFinish() — onFinish() only runs on whichever
            // single thread happens to call it, so with parallel="methods" or
            // parallel="classes" every OTHER worker thread's ExtentTest entry
            // would otherwise leak for the life of the JVM.
            test.remove();
            videoForExtent.remove();
        }
    }

    /** videoForExtent is populated (or explicitly cleared to null) by
     *  keepOrDiscardVideo() in afterInvocation, which — per this class's
     *  ordering guarantee — always runs before this callback. Extent can't
     *  play AVI/TSCC inline, so this is a plain download link, same as the
     *  Allure side. */
    private void linkVideoIntoExtent() {
        File video = videoForExtent.get();
        if (video == null || test.get() == null) {
            return;
        }
        // Relative, not absolute: an absolute path only resolves on the machine
        // that ran the test, which breaks the moment the report is archived/
        // downloaded/opened elsewhere — every other embedded artifact in this
        // framework's reports (Extent's own screenshots, Allure attachments)
        // is self-contained/portable for exactly this reason. Video is only
        // ever recorded for web (BaseTest) tests, whose Extent report always
        // lives at target/extent-reports/<site>/<browser>/<suite>/index.html
        // (see ExtentManager.create()) — 4 levels above target/, so the path
        // back down into target/videos/<file> is fixed at "../../../../".
        String relativeToTarget = "../../../../videos/" + video.getName();
        test.get().info("Recording: <a href=\"" + relativeToTarget
            + "\" target=\"_blank\">" + video.getName() + "</a>");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        try {
            // ── Extent: log failure + embed screenshot ───────────────────────────
            if (test.get() != null) {
                test.get().fail("Test Failed");
                test.get().fail(result.getThrowable());

                byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(getDriver(result));
                if (bytes.length > 0) {
                    test.get().addScreenCaptureFromBase64String(
                        "data:image/png;base64," + ScreenshotUtil.toBase64(bytes),
                        "Failure Screenshot"
                    );
                }
                linkVideoIntoExtent();
            }
            HumanActions.postTestPause();
        } finally {
            test.remove();
            videoForExtent.remove();
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        try {
            // ── Extent: mark skipped + log reason + embed screenshot ─────────────
            // BUG FIX: this used to stop at logging the skip reason — no
            // screenshot was ever attached, even when a live driver existed
            // (e.g. a retried-but-still-failed intermediate attempt, which
            // TestNG reports as SKIP rather than FAILURE — see
            // TestListener.afterTestInvocation()'s comment on the Allure
            // side of this same fix). Mirrors onTestFailure()'s screenshot
            // handling exactly; captureScreenshotAsBytes() already no-ops
            // cleanly if the driver is null (e.g. a dependency-skip that
            // never reached setUp()).
            if (test.get() != null) {
                test.get().skip("Test Skipped");
                if (result.getThrowable() != null) {
                    test.get().skip(result.getThrowable());
                }

                byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(getDriver(result));
                if (bytes.length > 0) {
                    test.get().addScreenCaptureFromBase64String(
                        "data:image/png;base64," + ScreenshotUtil.toBase64(bytes),
                        "Skip Screenshot"
                    );
                }
                linkVideoIntoExtent();
            }
            // ── Allure: handled in afterTestInvocation() (IInvokedMethodListener) ──
        } finally {
            test.remove();
            videoForExtent.remove();
        }
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        // Treat timeout exactly like a regular failure
        onTestFailure(result);
    }

    @Override
    public void onFinish(ITestContext context) {
        extent().flush();
        // Reset the ExtentManager singleton after each suite finishes.
        // Without this, if two sites run sequentially in the same JVM (e.g. a
        // future single-mvn multi-site run), the second site inherits the first
        // site's report name, system info, and output path — corrupting both reports.
        ExtentManager.reset();
        AllureEnvironmentWriter.reset();
    }
}
