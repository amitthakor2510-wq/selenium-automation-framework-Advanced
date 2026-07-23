package com.automation.core.utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

import java.util.List;
import java.util.logging.Logger;

/**
 * Best-effort diagnostics captured only when a test fails, and attached
 * to the Allure report alongside the failure screenshot so a failure can
 * usually be triaged without re-running the test locally.
 *
 * Both methods are deliberately defensive: a WebDriver that has already
 * crashed/quit, or a browser that doesn't support a given capability
 * (e.g. Firefox has no browser-console log endpoint), must never turn a
 * genuine test failure into a secondary NullPointerException inside the
 * listener itself.
 */
public final class FailureDiagnostics {

    private static final Logger logger = Logger.getLogger(FailureDiagnostics.class.getName());

    private FailureDiagnostics() {
    }

    /** Full HTML of the page at the moment of failure, or "" if unavailable. */
    public static String capturePageSource(WebDriver driver) {
        if (driver == null) return "";
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            logger.warning("Could not capture page source: " + e.getMessage());
            return "";
        }
    }

    /**
     * Browser (JS console) log entries since the last call, newest-friendly
     * plain text. Chromium-based drivers only (see DriverFactory's
     * goog:loggingPrefs); Firefox/geckodriver returns an empty string.
     */
    public static String captureBrowserConsoleLogs(WebDriver driver) {
        if (driver == null) return "";
        try {
            List<LogEntry> entries = driver.manage().logs().get(LogType.BROWSER).getAll();
            if (entries.isEmpty()) {
                return "(no console output captured)";
            }
            StringBuilder sb = new StringBuilder();
            for (LogEntry entry : entries) {
                sb.append('[').append(entry.getLevel()).append("] ")
                        .append(entry.getMessage()).append(System.lineSeparator());
            }
            return sb.toString();
        } catch (Exception e) {
            // Firefox/geckodriver (and some Grid nodes) don't expose this log
            // type at all — that's expected, not an error worth surfacing.
            return "(browser console logs not supported by this driver)";
        }
    }
}