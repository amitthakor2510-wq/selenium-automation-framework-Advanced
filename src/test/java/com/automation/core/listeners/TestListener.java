package com.automation.core.listeners;

import com.automation.core.base.BaseTest;
import com.automation.core.report.ExtentManager;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import org.openqa.selenium.WebDriver;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TestListener implements ITestListener {

    private static final ExtentReports extent = ExtentManager.getInstance();

    // ThreadLocal ensures each thread has its own ExtentTest
    private static final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    @Override
    public void onTestStart(ITestResult result) {
        // Use description if provided, otherwise fall back to method name
        String testName = result.getMethod().getDescription();
        if (testName == null || testName.isEmpty()) {
            testName = result.getName();
        }

        ExtentTest extentTest = extent.createTest(testName);
        test.set(extentTest);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        if (test.get() != null) {
            test.get().pass("Test Passed");
        }
        applyPostTestHumanPause(result);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        Object testInstance = result.getInstance();
        WebDriver driver = (testInstance instanceof BaseTest) ? ((BaseTest) testInstance).getDriver() : null;

        if (driver != null) {
            try {
                // 1. Capture the screenshot natively as a Base64 text string
                String base64Data = ScreenshotUtil.captureScreenshotAsBase64(driver);

                if (test.get() != null) {
                    test.get().fail("Test Failed");
                    test.get().fail(result.getThrowable());

                    // 2. Embed the Base64 image directly inside the Extent HTML report markup
                    if (base64Data != null && !base64Data.isEmpty()) {
                        test.get().addScreenCaptureFromBase64String("data:image/png;base64," + base64Data);
                    }
                }
            } catch (Exception e) {
                System.out.println("Could not capture cloud screenshot: " + e.getMessage());
            }
        }

        applyPostTestHumanPause(result);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        // noop
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        // noop
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        onTestFailure(result);
    }

    @Override
    public void onFinish(ITestContext context) {
        extent.flush();
    }

    private void applyPostTestHumanPause(ITestResult result) {
        // Post-test pause runs regardless of whether the test class exposes
        // driver-level helpers - it's a global "settle" beat, not tied to
        // any one page action, so it lives here rather than in BaseTest.
        HumanActions.postTestPause();
    }
}
