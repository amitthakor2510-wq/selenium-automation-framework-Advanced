package com.automation.core.driver;

import java.io.File;
import java.io.InputStream;

/**
 * Locates the Brave and Firefox executables (system-property override,
 * environment, then the usual per-OS install locations), split out of
 * {@link DriverFactory}. Package-private: only {@link DriverFactory} uses it.
 */
final class BrowserBinaryLocator {

    private BrowserBinaryLocator() {
    }

    static String findBraveBinary() {
        // Allow explicit override via system property
        String override = System.getProperty("brave.binary");
        if (override != null && new File(override).exists()) {
            return override;
        }

        String[] candidates = {
            // Linux
            "/usr/bin/brave-browser",
            "/usr/bin/brave",
            "/opt/brave.com/brave/brave",
            // macOS
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            // Windows
            System.getenv("LOCALAPPDATA") != null
                ? System.getenv("LOCALAPPDATA") + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
                : null,
            "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "C:\\Program Files (x86)\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
        };

        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    static String findFirefoxBinary() {
        // 1. Explicit -Dfirefox.binary=... CLI/pom override
        String override = System.getProperty("firefox.binary");
        if (override != null && !override.trim().isEmpty() && new File(override).exists()) {
            return override;
        }

        // 2. Probe common install paths (order matters — most common first per OS)
        String[] candidates = {
            // Ubuntu 22+/24 Snap install (most common on modern Ubuntu)
            "/snap/bin/firefox",
            // Ubuntu/Debian apt install
            "/usr/bin/firefox",
            // Older Ubuntu/Fedora
            "/usr/lib/firefox/firefox",
            "/usr/lib64/firefox/firefox",
            // Fedora/RHEL flatpak
            "/var/lib/flatpak/exports/bin/org.mozilla.firefox",
            // macOS
            "/Applications/Firefox.app/Contents/MacOS/firefox",
            "/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
            "/Applications/Firefox Nightly.app/Contents/MacOS/firefox",
            // Windows
            System.getenv("PROGRAMFILES") != null
                ? System.getenv("PROGRAMFILES") + "\\Mozilla Firefox\\firefox.exe" : null,
            System.getenv("PROGRAMFILES(X86)") != null
                ? System.getenv("PROGRAMFILES(X86)") + "\\Mozilla Firefox\\firefox.exe" : null,
        };

        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // 3. Try finding firefox on PATH via which/where command
        //
        // Process streams/handles are explicitly closed and the process is
        // waited on and destroyed — leaving them open leaks file descriptors
        // over a long test run. "where" on Windows can also print more than
        // one match (one per line), so only the first line is taken instead
        // of treating the whole (possibly multi-line) output as a single path.
        try {
            String finder = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "where" : "which";
            Process p = new ProcessBuilder(finder, "firefox").start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                p.waitFor();
                p.destroy();
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            if (!firstLine.isEmpty() && new File(firstLine).exists()) {
                return firstLine;
            }
        } catch (Exception ignored) {
            // fall through — geckodriver will search PATH itself
        }

        return null;  // geckodriver will attempt to find Firefox itself
    }
}
