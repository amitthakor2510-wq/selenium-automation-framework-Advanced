package com.automation.core.utils;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class ScreenshotUtil {
    private static final Logger logger = Logger.getLogger(ScreenshotUtil.class.getName());

    private ScreenshotUtil() {
        // Private constructor to hide the implicit public one
    }

    public static String captureScreenshot(WebDriver driver, String testName) {
        if (driver == null) {
            return null;
        }

        String timestamp = LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filePath = "target/screenshots/" + testName + "_" + timestamp + ".png";

        try {
            TakesScreenshot ts = (TakesScreenshot) driver;
            File src = ts.getScreenshotAs(OutputType.FILE);

            Files.createDirectories(Paths.get("target/screenshots"));
            Files.copy(src.toPath(), Paths.get(filePath));

        } catch (IOException e) {
            logger.warning("IOException while saving file screenshot: " + e.getMessage());
            // Nothing was actually written — returning filePath here would give
            // callers (e.g. Allure attachments) a path to a file that doesn't exist.
            return null;
        } catch (Exception e) {
            // A driver session that's already crashed/quit (e.g. WebDriverException,
            // NoSuchSessionException) must not turn a genuine test failure into a
            // secondary uncaught exception here — same defensive rule this class's
            // other two capture methods (and FailureDiagnostics) already follow.
            logger.warning("Could not capture file screenshot: " + e.getMessage());
            return null;
        }

        return filePath;
    }

    /**
     * Captures a screenshot and converts it natively to a Base64 string for cloud environments.
     * This avoids broken image paths when viewing reports on GitHub Pages or unpacked ZIPs.
     */
    public static String captureScreenshotAsBase64(WebDriver driver) {
        if (driver == null) {
            return "";
        }
        try {
            TakesScreenshot ts = (TakesScreenshot) driver;
            return ts.getScreenshotAs(OutputType.BASE64);
        } catch (Exception e) {
            logger.warning("Exception while taking base64 screenshot: " + e.getMessage());
            return "";
        }
    }

    public static byte[] captureScreenshotAsBytes(WebDriver driver) {
        if (driver == null) {
            return new byte[0];
        }
        try {
            return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        } catch (Exception e) {
            logger.warning("Could not take screenshot: " + e.getMessage());
            return new byte[0];
        }
    }

    public static String toBase64(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
