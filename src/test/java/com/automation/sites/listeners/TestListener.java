package com.automation.sites.listeners;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.report.AllureEnvironmentWriter;
import com.automation.core.report.ExtentManager;
import com.automation.core.utils.FailureDiagnostics;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import io.qameta.allure.Allure;
import org.openqa.selenium.WebDriver;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TestListener implements ITestListener {

    private static final ExtentReports extent = ExtentManager.getInstance();
    private static final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

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

    /**
     * Guards every Allure.* call below. AllureTestNg (Allure's own auto-registered
     * TestNG listener) is what actually opens the "current test case" that
     * Allure.label/parameter/addAttachment write into — and depending on listener
     * registration order that can race with our own onTestStart/onTestFailure/etc.
     * firing first, producing harmless-but-noisy
     * "ERROR io.qameta.allure.AllureLifecycle - Could not update test case: no
     * test case running" log spam (and a silently-dropped attachment/label).
     * Checking first avoids the noise; it does mean that specific label/screenshot
     * is skipped for that one test run rather than attached, but that's strictly
     * better than the previous behavior, which attempted it anyway and failed
     * the exact same way, just loudly.
     */
    private boolean isAllureTestCaseOpen() {
        return Allure.getLifecycle().getCurrentTestCaseOrStep().isPresent();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        synchronized (extent) {
            extentTest = extent.createTest(
                testName,
                (description != null && !description.isEmpty()) ? description : ""
            );
        }
        test.set(extentTest);

        // ── Allure: severity + run context, visible on every test's page ────────
        if (isAllureTestCaseOpen()) {
            tagSeverity(result);
            Allure.parameter("Site", ConfigReader.get("site", ConfigReader.getActiveSite()));
            Allure.parameter("Browser", ConfigReader.get("browser", "chrome"));
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        try {
            // ── Extent: pass log only — no screenshot on success ─────────────────
            if (test.get() != null) {
                test.get().pass("Test Passed");
            }

            // ── Allure: attach success screenshot ────────────────────────────────
            WebDriver driver = getDriver(result);
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
            if (bytes.length > 0 && isAllureTestCaseOpen()) {
                Allure.addAttachment(
                    "Pass Screenshot — " + result.getMethod().getMethodName(),
                    "image/png",
                    new ByteArrayInputStream(bytes),
                    "png"
                );
            }

            HumanActions.postTestPause();
        } finally {
            // Clean up this thread's ThreadLocal entry right here, rather than
            // relying solely on onFinish() — onFinish() only runs on whichever
            // single thread happens to call it, so with parallel="methods" or
            // parallel="classes" every OTHER worker thread's ExtentTest entry
            // would otherwise leak for the life of the JVM.
            test.remove();
        }
    }

    @Override
    public void onTestFailure(ITestResult result) {
        try {
            WebDriver driver = getDriver(result);
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);

            // ── Extent: log failure + embed screenshot ───────────────────────────
            if (test.get() != null) {
                test.get().fail("Test Failed");
                test.get().fail(result.getThrowable());

                if (bytes.length > 0)  {
                    test.get().addScreenCaptureFromBase64String(
                        "data:image/png;base64," + ScreenshotUtil.toBase64(bytes),
                        "Failure Screenshot"
                    );
                }
            }

            // ── Allure: attach failure screenshot + diagnostics for triage ───────
            // Guarded once for the whole block rather than per-call: if the test
            // case isn't open, none of these Allure.* calls will land anyway.
            if (isAllureTestCaseOpen()) {
                if (bytes.length > 0) {
                    Allure.addAttachment(
                        "Failure Screenshot — " + result.getMethod().getMethodName(),
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
                    Allure.parameter("Failed URL", safeCurrentUrl(driver));
                }
            }

            HumanActions.postTestPause();
        } finally {
            test.remove();
        }
    }

    private String safeCurrentUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        try {
            // ── Extent: mark skipped + log reason ───────────────────────────────
            if (test.get() != null) {
                test.get().skip("Test Skipped");
                if (result.getThrowable() != null) {
                    test.get().skip(result.getThrowable());
                }
            }
            // ── Allure: no screenshot on skip ────────────────────────────────────
        } finally {
            test.remove();
        }
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        // Treat timeout exactly like a regular failure
        onTestFailure(result);
    }

    // AFTER
    @Override
    public void onFinish(ITestContext context) {
        extent.flush();
        // FIX #3: Reset the ExtentManager singleton after each suite finishes.
        // Without this, if two sites run sequentially in the same JVM (e.g. a
        // future single-mvn multi-site run), the second site inherits the first
        // site's report name, system info, and output path — corrupting both reports.
        ExtentManager.reset();
        AllureEnvironmentWriter.reset();
    }
}
