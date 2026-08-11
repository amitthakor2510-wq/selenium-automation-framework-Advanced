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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenshotUtil {
    private static final Logger logger = LoggerFactory.getLogger(ScreenshotUtil.class);

    private ScreenshotUtil() {
        // Private constructor to hide the implicit public one
    }

    public static String captureScreenshot(WebDriver driver, String testName) {
        if (driver == null) {
            return null;
        }

        // BUG FIX: the previous "yyyyMMdd_HHmmss" timestamp only had
        // second-level precision and Files.copy() was called without
        // REPLACE_EXISTING. Two screenshots for the same testName within
        // the same second (e.g. back-to-back SCREENSHOT keyword steps, or
        // just a fast test) collided on the same file path, Files.copy()
        // threw FileAlreadyExistsException, and that was silently swallowed
        // by the catch block below — losing the second screenshot with no
        // visible error. Millisecond precision plus a short random suffix
        // makes same-run collisions effectively impossible, and
        // REPLACE_EXISTING means even a genuine collision no longer fails.
        String timestamp = LocalDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS"));
        String uniqueSuffix = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        String filePath = "target/screenshots/" + testName + "_" + timestamp + "_" + uniqueSuffix + ".png";

        try {
            TakesScreenshot ts = (TakesScreenshot) driver;
            File src = ts.getScreenshotAs(OutputType.FILE);

            Files.createDirectories(Paths.get("target/screenshots"));
            Files.copy(src.toPath(), Paths.get(filePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            logger.warn("IOException while saving file screenshot: " + e.getMessage());
            // Nothing was actually written — returning filePath here would give
            // callers (e.g. Allure attachments) a path to a file that doesn't exist.
            return null;
        } catch (Exception e) {
            // A driver session that's already crashed/quit (e.g. WebDriverException,
            // NoSuchSessionException) must not turn a genuine test failure into a
            // secondary uncaught exception here — same defensive rule this class's
            // other two capture methods (and FailureDiagnostics) already follow.
            logger.warn("Could not capture file screenshot: " + e.getMessage());
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
            logger.warn("Exception while taking base64 screenshot: " + e.getMessage());
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
            logger.warn("Could not take screenshot: " + e.getMessage());
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
