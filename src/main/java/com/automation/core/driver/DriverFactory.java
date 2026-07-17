package com.automation.core.driver;

import com.automation.core.config.ConfigReader;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Single place responsible for creating a WebDriver instance.
 * Supports chrome / firefox / edge / brave, and a headless=true/false
 * config flag so Jenkins can run headless while local dev
 * runs with a visible browser.
 */
public final class DriverFactory {

    private DriverFactory() {
    }

    public static WebDriver createDriver() {
        String browser = ConfigReader.get("browser", "chrome").toLowerCase();
        boolean headless = ConfigReader.getBoolean("headless", false);

        switch (browser) {
            case "chrome":
                return createChromeDriver(headless);
            case "firefox":
                return createFirefoxDriver(headless);
            case "edge":
                return createEdgeDriver(headless);
            case "brave":
                return createBraveDriver(headless);
            default:
                throw new RuntimeException("Browser not supported: " + browser
                        + ". Supported: chrome, firefox, edge, brave");
        }
    }

    // ── Chrome ────────────────────────────────────────────────────────────────

    private static WebDriver createChromeDriver(boolean headless) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = buildChromeOptions(headless);
        return new ChromeDriver(options);
    }

    // ── Brave ─────────────────────────────────────────────────────────────────
    // Brave is Chromium-based: same chromedriver, just point binary at Brave.

    private static WebDriver createBraveDriver(boolean headless) {
        // Locate Brave binary — check common install paths across OSes
        String braveBinary = findBraveBinary();
        if (braveBinary == null) {
            throw new RuntimeException(
                    "Brave browser binary not found. Install Brave or set -Dbrave.binary=/path/to/brave");
        }

        WebDriverManager.chromedriver().setup();
        ChromeOptions options = buildChromeOptions(headless);
        options.setBinary(braveBinary);
        System.out.println("[DriverFactory] Using Brave binary: " + braveBinary);
        return new ChromeDriver(options);
    }

    private static String findBraveBinary() {
        // Allow explicit override via system property
        String override = System.getProperty("brave.binary");
        if (override != null && new File(override).exists()) return override;

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
            if (path != null && new File(path).exists()) return path;
        }
        return null;
    }

    /** Shared ChromeOptions used by both Chrome and Brave */
    private static ChromeOptions buildChromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        String downloadPath = getDownloadPath();
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadPath);
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("safebrowsing.enabled", true);
        options.setExperimentalOption("prefs", prefs);

        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
        } else {
            options.addArguments("--start-maximized");
        }
        return options;
    }

    // ── Firefox ───────────────────────────────────────────────────────────────

    private static WebDriver createFirefoxDriver(boolean headless) {
        // Apply WDM cache settings explicitly so drivers are not re-downloaded
        // on every run (pom.xml passes these as system properties).
        String wdmCache = System.getProperty("wdm.cachePath",
                System.getProperty("user.home") + "/.wdm");
        System.setProperty("wdm.cachePath", wdmCache);
        System.setProperty("wdm.forceDownload",
                System.getProperty("wdm.forceDownload", "false"));

        WebDriverManager.firefoxdriver().setup();

        String firefoxBinary = findFirefoxBinary();
        if (firefoxBinary != null) {
            System.out.println("[DriverFactory] Using Firefox binary: " + firefoxBinary);
        } else {
            System.out.println("[DriverFactory] Firefox binary not found on known paths — " +
                    "geckodriver will search PATH. If tests hang, install Firefox or pass " +
                    "-Dfirefox.binary=/path/to/firefox");
        }

        FirefoxOptions options = new FirefoxOptions();

        if (firefoxBinary != null) {
            options.setBinary(firefoxBinary);
        }

        // Disable sandbox on Linux CI environments
        options.addPreference("security.sandbox.content.level", 0);
        options.addPreference("security.sandbox.gpu.level", 0);
        options.addPreference("security.sandbox.media.level", 0);

        // Download preferences
        options.addPreference("browser.download.folderList", 2);
        options.addPreference("browser.download.dir", getDownloadPath());
        options.addPreference("browser.helperApps.neverAsk.saveToDisk",
                "application/octet-stream,application/zip,text/csv");

        if (headless) {
            options.addArguments("-headless");
            options.addArguments("--width=1920");
            options.addArguments("--height=1080");
        }

        return new FirefoxDriver(options);
    }

    private static String findFirefoxBinary() {
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
            if (path != null && new File(path).exists()) return path;
        }

        // 3. Try finding firefox on PATH via which/where command
        try {
            String whichCmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "where firefox" : "which firefox";
            Process p = Runtime.getRuntime().exec(whichCmd);
            String result = new String(p.getInputStream().readAllBytes()).trim();
            if (!result.isEmpty() && new File(result).exists()) return result;
        } catch (Exception ignored) {}

        return null;  // geckodriver will attempt to find Firefox itself
    }

    // ── Edge ──────────────────────────────────────────────────────────────────

    private static WebDriver createEdgeDriver(boolean headless) {
        WebDriverManager.edgedriver().setup();

        EdgeOptions options = new EdgeOptions();
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-allow-origins=*");

        String downloadPath = getDownloadPath();
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadPath);
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        options.setExperimentalOption("prefs", prefs);

        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
        } else {
            options.addArguments("--start-maximized");
        }

        return new EdgeDriver(options);
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    private static String getDownloadPath() {
        String path = System.getProperty("user.dir")
                + File.separator + "target"
                + File.separator + "downloads";
        new File(path).mkdirs();
        return path;
    }
}