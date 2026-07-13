package com.automation.core.listeners;

import com.automation.core.base.DriverProvider;
import com.automation.core.report.ExtentManager;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class TestListener implements ITestListener {

    private static final ExtentReports extent = ExtentManager.getInstance();
    private static final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    @Override
    public void onTestStart(ITestResult result) {
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
        HumanActions.postTestPause();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        Object testInstance = result.getInstance();
        WebDriver driver = (testInstance instanceof DriverProvider)
                ? ((DriverProvider) testInstance).getDriver()
                : null;

        if (driver != null) {
            try {
                // ── Extent: embed Base64 screenshot ──────────────────────
                String base64Data = ScreenshotUtil.captureScreenshotAsBase64(driver);
                if (test.get() != null) {
                    test.get().fail("Test Failed");
                    test.get().fail(result.getThrowable());
                    if (base64Data != null && !base64Data.isEmpty()) {
                        test.get().addScreenCaptureFromBase64String(
                                "data:image/png;base64," + base64Data);
                    }
                }

                // ── Allure: attach screenshot as PNG ─────────────────────
                byte[] screenshotBytes = ((TakesScreenshot) driver)
                        .getScreenshotAs(OutputType.BYTES);
                InputStream stream = new ByteArrayInputStream(screenshotBytes);
                io.qameta.allure.Allure.addAttachment("Failure Screenshot", "image/png", stream, "png");

            } catch (Exception e) {
                System.out.println("Could not capture screenshot: " + e.getMessage());
            }
        }

        HumanActions.postTestPause();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        if (test.get() != null) {
            test.get().skip("Test Skipped");
        }
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        onTestFailure(result);
    }

    @Override
    public void onFinish(ITestContext context) {
        extent.flush();
    }
}