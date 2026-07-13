package com.automation.core.utils;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ScreenshotUtil {

    // Keeps your original local file-saving logic intact
    public static String captureScreenshot(WebDriver driver, String testName) {

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filePath = "target/screenshots/" + testName + "_" + timestamp + ".png";

        try {
            // FIXED: Removed the stray variable declaration and verified clean type casting
            TakesScreenshot ts = (TakesScreenshot) driver;
            File src = ts.getScreenshotAs(OutputType.FILE);

            Files.createDirectories(Paths.get("target/screenshots"));
            Files.copy(src.toPath(), Paths.get(filePath));

        } catch (IOException e) {
            System.out.println("IOException while saving file screenshot: " + e.getMessage());
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
            System.out.println("Exception while taking base64 screenshot: " + e.getMessage());
            return "";
        }
    }
}
