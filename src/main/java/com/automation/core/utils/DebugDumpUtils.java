package com.automation.core.utils;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes the current page source to target/debug-dumps/ for offline
 * inspection when a page object hits an unexpected state (element not
 * found after a wait, URL not reached, etc.) — call this right before
 * throwing/failing so the dump captures the actual DOM at that moment.
 *
 * Promoted out of BasePage.dumpPageForDebugging(String) into core/utils,
 * per the tech-debt item docs/roadmap.md had tracked since this was three
 * separate hand-rolled copies in BookStoreApplicationPage/CheckBoxPage/
 * ProfilePage (one used logger.info while the other two used logger.fine
 * for the identical situation — an inconsistency rather than an
 * intentional difference). Those three were already consolidated into one
 * copy on BasePage in an earlier pass, closing the original duplication;
 * this pass moves that one copy the rest of the way to match what the
 * roadmap actually asked for (core/utils, matching the pattern every other
 * shared driver-facing helper in this project already follows — see
 * ElementUtils, FailureDiagnostics, ScreenshotUtil) and updates the
 * roadmap accordingly. BasePage.dumpPageForDebugging(String) now just
 * delegates here — existing page-object call sites (unqualified
 * dumpPageForDebugging("...") calls inherited from BasePage) are
 * unaffected.
 *
 * {@code callerClass} is passed in explicitly (rather than this class
 * calling Class-of-driver or similar) so log output still reads as coming
 * from whichever page object actually triggered the dump — e.g.
 * "CheckBoxPage", not "DebugDumpUtils" — the same behavior the original
 * per-page-object copies and BasePage's consolidated copy both had via
 * LoggerFactory.getLogger(getClass()).
 */
public final class DebugDumpUtils {

    private DebugDumpUtils() {
    }

    public static void dumpPageForDebugging(WebDriver driver, Class<?> callerClass, String label) {
        Logger logger = LoggerFactory.getLogger(callerClass);
        try {
            Path dir = Paths.get("target", "debug-dumps");
            Files.createDirectories(dir);
            String fileName = label.replaceAll("[^a-zA-Z0-9]", "")
                + "-" + System.currentTimeMillis() + ".html";
            Path file = dir.resolve(fileName);
            Files.writeString(file, driver.getPageSource());
            logger.debug("  DEBUG full page source written to: " + file.toAbsolutePath());
        } catch (Exception writeEx) {
            logger.debug("  DEBUG could not write page source dump: " + writeEx.getMessage());
        }
        logger.debug("  DEBUG current URL: " + driver.getCurrentUrl());
    }
}
