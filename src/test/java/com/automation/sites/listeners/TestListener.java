package com.automation.sites.listeners;

import com.automation.core.base.DriverProvider;
import com.automation.core.report.ExtentManager;
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
            // ── Extent: pass log only — no screenshot on success ─────────────────
            if (test.get() != null) {
                test.get().pass("Test Passed");
            }

            // ── Allure: attach success screenshot ────────────────────────────────
            WebDriver driver = getDriver(result);
            byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
            if (bytes.length > 0) {
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

            // ── Allure: attach failure screenshot ────────────────────────────────
            if (bytes.length > 0) {
                Allure.addAttachment(
                        "Failure Screenshot — " + result.getMethod().getMethodName(),
                        "image/png",
                        new ByteArrayInputStream(bytes),
                        "png"
                );
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
        // ThreadLocal cleanup now happens per-thread in onTestSuccess/
        // onTestFailure/onTestSkipped above, so nothing to remove here.
    }
}