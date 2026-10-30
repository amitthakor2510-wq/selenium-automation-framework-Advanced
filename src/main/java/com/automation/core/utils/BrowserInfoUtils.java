package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;

import java.util.Locale;

/**
 * Browser and operating-system facts for reports: the configured browser, the OS it runs on, and (when a
 * driver is available) the browser version the driver actually started. Every method is null-safe and
 * never throws - a report must not fail because a label could not be worked out.
 */
public final class BrowserInfoUtils {

    private static final String OS_NAME = System.getProperty("os.name", "unknown");
    private static final String OS_LOWER = OS_NAME.toLowerCase(Locale.ROOT);

    private BrowserInfoUtils() {
    }

    /** The configured browser in upper case, e.g. {@code CHROME}. */
    public static String getBrowserInfo() {
        try {
            return ConfigReader.get("browser", "chrome").trim().toUpperCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "CHROME";
        }
    }

    /** OS name and version, e.g. {@code Windows 11 10.0}. */
    public static String getOSInfo() {
        String version = System.getProperty("os.version", "");
        return version.isBlank() ? OS_NAME : OS_NAME + " " + version;
    }

    /** Browser version the driver reports, or {@code ""} when unknown (no driver, remote without caps...). */
    public static String getBrowserVersion(WebDriver driver) {
        try {
            if (driver instanceof HasCapabilities has) {
                Capabilities caps = has.getCapabilities();
                return caps == null || caps.getBrowserVersion() == null ? "" : caps.getBrowserVersion();
            }
        } catch (RuntimeException e) {
            // A closed or crashed session throws here - the version is a nicety, not a requirement.
        }
        return "";
    }

    /** Short label for the Extent "device" filter: {@code CHROME / Windows 11}. */
    public static String deviceLabel() {
        return getBrowserInfo() + " / " + OS_NAME;
    }

    /** One line for a test's log: {@code Browser: CHROME 126.0.6478.61 on Windows 11 10.0}. */
    public static String describe(WebDriver driver) {
        String version = getBrowserVersion(driver);
        return "Browser: " + getBrowserInfo() + (version.isBlank() ? "" : " " + version) + " on " + getOSInfo();
    }

    public static boolean isWindows() {
        return OS_LOWER.contains("win");
    }

    public static boolean isMac() {
        return OS_LOWER.contains("mac");
    }

    public static boolean isLinux() {
        return OS_LOWER.contains("nix") || OS_LOWER.contains("nux") || OS_LOWER.contains("aix");
    }
}
