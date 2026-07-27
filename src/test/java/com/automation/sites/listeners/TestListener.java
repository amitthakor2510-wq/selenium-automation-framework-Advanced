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
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
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
        if (isAllureTestCaseOpen()) {
            tagSeverity(result);
            Allure.parameter("Site", ConfigReader.get("site", ConfigReader.getActiveSite()));
            Allure.parameter("Browser", ConfigReader.get("browser", "chrome"));
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (!method.isTestMethod()) {
            return;
        }

        WebDriver driver = getDriver(result);

        if (result.getStatus() == ITestResult.SUCCESS) {
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
            if (bytes.length > 0 && isAllureTestCaseOpen()) {
                Allure.addAttachment(
                    "Pass Screenshot — " + result.getMethod().getMethodName(),
                    "image/png",
                    new ByteArrayInputStream(bytes),
                    "png"
                );
            }
            return;
        }

        if (result.getStatus() == ITestResult.FAILURE) {
            if (!isAllureTestCaseOpen()) {
                return;
            }
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
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
        synchronized (extent) {
            extentTest = extent.createTest(
                testName,
                (description != null && !description.isEmpty()) ? description : ""
            );
        }
        test.set(extentTest);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        try {
            if (test.get() != null) {
                test.get().pass("Test Passed");
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
            }
            HumanActions.postTestPause();
        } finally {
            test.remove();
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

    @Override
    public void onFinish(ITestContext context) {
        extent.flush();
        // Reset the ExtentManager singleton after each suite finishes.
        // Without this, if two sites run sequentially in the same JVM (e.g. a
        // future single-mvn multi-site run), the second site inherits the first
        // site's report name, system info, and output path — corrupting both reports.
        ExtentManager.reset();
        AllureEnvironmentWriter.reset();
    }
}
