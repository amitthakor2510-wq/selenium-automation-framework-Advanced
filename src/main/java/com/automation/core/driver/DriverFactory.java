package com.automation.core.driver;

import java.util.logging.Logger;

import com.automation.core.config.ConfigReader;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Single place responsible for creating a WebDriver instance.
 * Supports chrome / firefox / edge / brave, and a headless=true/false
 * config flag so Jenkins can run headless while local dev
 * runs with a visible browser.
 *
 * DOCKER / SELENIUM GRID:
 * When grid.enabled=true (or -Dgrid.enabled=true), the browser is not
 * launched locally — instead a RemoteWebDriver session is opened against
 * the Selenium Grid hub at grid.url (default: http://localhost:4444/wd/hub).
 * This is how the framework runs inside docker-compose, where the browsers
 * live in separate selenium/node-* containers. See docker-compose.yml.
 */
public final class DriverFactory {

    private static final Logger logger = Logger.getLogger(DriverFactory.class.getName());

    private DriverFactory() {
    }

    public static WebDriver createDriver() {
        String browser = ConfigReader.get("browser", "chrome").toLowerCase();
        boolean headless = ConfigReader.getBoolean("headless", false);

        if (ConfigReader.getBoolean("grid.enabled", false)) {
            return createRemoteDriver(browser, headless);
        }

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

    // ── Selenium Grid / Docker (RemoteWebDriver) ────────────────────────────────

    private static WebDriver createRemoteDriver(String browser, boolean headless) {
        String gridUrl = ConfigReader.get("grid.url", "http://localhost:4444/wd/hub");

        Object options;
        switch (browser) {
            case "chrome":
            case "brave":
                options = buildChromeOptions(headless);
                break;
            case "firefox":
                FirefoxOptions ffOptions = new FirefoxOptions();
                ffOptions.setPageLoadStrategy(PageLoadStrategy.EAGER);
                if (headless) {
                    ffOptions.addArguments("-headless");
                }
                options = ffOptions;
                break;
            case "edge":
                EdgeOptions edgeOptions = new EdgeOptions();
                edgeOptions.setPageLoadStrategy(PageLoadStrategy.EAGER);
                if (headless) {
                    edgeOptions.addArguments("--headless=new");
                }
                options = edgeOptions;
                break;
            default:
                throw new RuntimeException("Browser not supported on grid: " + browser
                    + ". Supported: chrome, firefox, edge, brave");
        }

        try {
            URL hubUrl = URI.create(gridUrl).toURL();
            logger.info("[DriverFactory] Connecting to Selenium Grid at " + gridUrl
                + " (browser=" + browser + ", headless=" + headless + ")");
            RemoteWebDriver remoteDriver;
            if (options instanceof ChromeOptions co) {
                remoteDriver = new RemoteWebDriver(hubUrl, co);
            } else if (options instanceof FirefoxOptions fo) {
                remoteDriver = new RemoteWebDriver(hubUrl, fo);
            } else {
                remoteDriver = new RemoteWebDriver(hubUrl, (EdgeOptions) options);
            }

            // Without this, sendKeys() on a file input (upload.file.path, the
            // Practice Form "Select Picture" field, etc.) fails with "File not
            // found" — the path is only valid on the machine running this JVM
            // (the "tests" container), not on the Grid node actually running the
            // browser (the "chrome"/"firefox"/"edge" container). LocalFileDetector
            // makes RemoteWebDriver recognize when a sendKeys() value is a real
            // local file and transparently upload its bytes to the node first,
            // which is exactly what local (non-Grid) WebDriver sessions get for
            // free by sharing a filesystem with the browser.
            remoteDriver.setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
            return remoteDriver;
        } catch (MalformedURLException e) {
            throw new RuntimeException("[DriverFactory] Invalid grid.url: " + gridUrl, e);
        }
    }

    // ── Chrome ────────────────────────────────────────────────────────────────

    // NOTE: pinning chromedriver to an older build (149.x) was tried and did
    // NOT fix the Brave "Chrome instance exited" failure — it crashed
    // identically to the auto-matched 150.x build. That rules out a
    // driver/browser version mismatch as the cause. Since a real version
    // mismatch normally produces an explicit "This version of ChromeDriver
    // only supports Chrome version X" message rather than a silent crash,
    // the real cause is the Brave process itself dying on launch for a
    // reason ChromeDriver isn't surfacing (sandbox restriction, missing
    // shared library, snap confinement, GPU init failure, etc). Reverted to
    // normal auto-matched versioning; verboseLogging below is what actually
    // gets us the real reason.
    private static WebDriver createChromeDriver(boolean headless) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = buildChromeOptions(headless);
        ChromeDriverService service = buildVerboseLoggingService();
        return service != null ? new ChromeDriver(service, options) : new ChromeDriver(options);
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

        // .browserBinary() points chromedriver's version-matching at Brave,
        // so WebDriverManager downloads a chromedriver matched to Brave's
        // actual Chromium engine.
        WebDriverManager.chromedriver().browserBinary(braveBinary).setup();
        ChromeOptions options = buildChromeOptions(headless);
        options.setBinary(braveBinary);
        logger.info("[DriverFactory] Using Brave binary: " + braveBinary);

        // Verbose ChromeDriver logging: the generic SessionNotCreatedException
        // we've been getting ("Chrome instance exited") hides the actual
        // reason the Brave process died. This routes chromedriver's verbose
        // log (which DOES include Brave's own stderr/crash output) to a file
        // instead of losing it. Check this file after any failed run:
        //   target/logs/chromedriver-brave.log
        ChromeDriverService service = buildVerboseLoggingService();
        return service != null ? new ChromeDriver(service, options) : new ChromeDriver(options);
    }

    /**
     * Builds a ChromeDriverService with verbose logging enabled, writing to
     * target/logs/chromedriver-&lt;browser&gt;.log. Returns null (falls back to
     * default service) if the log directory can't be created, so this never
     * blocks a run — it's purely a diagnostic aid.
     */
    private static ChromeDriverService buildVerboseLoggingService() {
        try {
            String browser = ConfigReader.get("browser", "chrome").toLowerCase();
            File logDir = new File(System.getProperty("user.dir") + File.separator
                + "target" + File.separator + "logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                logger.warning("[DriverFactory] Could not create log directory: " + logDir);
                return null;
            }
            File logFile = new File(logDir, "chromedriver-" + browser + ".log");
            logger.info("[DriverFactory] Verbose chromedriver log: " + logFile.getAbsolutePath());
            return new ChromeDriverService.Builder()
                .withVerbose(true)
                .withLogFile(logFile)
                .build();
        } catch (Exception e) {
            logger.warning("[DriverFactory] Could not set up verbose chromedriver logging: "
                + e.getMessage());
            return null;
        }
    }

    private static String findBraveBinary() {
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

    /** Shared ChromeOptions used by both Chrome and Brave */
    private static ChromeOptions buildChromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();

        // ROOT CAUSE FIX (browser window staying open after quit()):
        // No --user-data-dir was ever set, so Chrome/Brave launched against
        // the OS default profile. Chromium is single-instance-per-profile —
        // if a Brave window is already running on that profile, the process
        // ChromeDriver just launched hands off to the already-running
        // instance and exits immediately. ChromeDriver's session then has no
        // real process left to signal, so quit() can't kill the actual
        // browser and the window (and any others in that instance) stays
        // open. Giving every session an isolated temp profile guarantees a
        // genuinely new, independently-owned process that quit() can
        // actually terminate.
        try {
            java.nio.file.Path tempProfile =
                java.nio.file.Files.createTempDirectory("selenium-profile-");
            options.addArguments("--user-data-dir=" + tempProfile.toAbsolutePath());
        } catch (java.io.IOException e) {
            logger.warning("[DriverFactory] Could not create temp profile dir: "
                + e.getMessage());
        }

        // Default (NORMAL) blocks driver.get() until the browser's full 'load'
        // event fires — on demoqa that means waiting for every ad/tracker script
        // too, not just the page's own content, which is what was making every
        // navigation take ~30s. EAGER returns once the DOM is parsed; our own
        // explicit waits (visibilityOfElementLocated etc.) already gate on the
        // specific elements each page actually needs before touching them.
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        // ROOT CAUSE (confirmed by isolating each ChromeDriver default switch
        // individually against the raw Brave binary): Brave's crashpad
        // startup self-check crashes (SIGTRAP, "elf_dynamic_array_reader.h:
        // tag not found") specifically when launched with the legacy
        // --test-type=webdriver switch. ChromeDriver injects this switch by
        // default on every session — it's not something we pass ourselves —
        // so it can only be removed via the excludeSwitches capability.
        // --enable-automation and --remote-debugging-port=0 were each tested
        // alone and do NOT trigger the crash, so only "test-type" is excluded.
        options.setExperimentalOption("excludeSwitches", java.util.List.of("test-type"));

        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-crash-reporter");
        options.addArguments("--disable-breakpad");
        options.addArguments("--noerrdialogs");

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

        // Capture browser (JS console) logs so TestListener can attach them to
        // the Allure report on failure — invaluable for diagnosing JS errors
        // that caused a UI interaction to fail. Chromium-based only (Chrome,
        // Edge, Brave); Firefox/geckodriver has no equivalent W3C capability.
        org.openqa.selenium.logging.LoggingPreferences logPrefs =
            new org.openqa.selenium.logging.LoggingPreferences();
        logPrefs.enable(org.openqa.selenium.logging.LogType.BROWSER, java.util.logging.Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

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
            logger.info("[DriverFactory] Using Firefox binary: " + firefoxBinary);
        } else {
            logger.info("[DriverFactory] Firefox binary not found on known paths — " +
                "geckodriver will search PATH. If tests hang, install Firefox or pass " +
                "-Dfirefox.binary=/path/to/firefox");
        }

        FirefoxOptions options = new FirefoxOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        if (firefoxBinary != null) {
            options.setBinary(firefoxBinary);
        }

        // Disable sandbox ONLY on CI — containers (Jenkins/GitLab agents)
        // often lack the Linux user-namespace permissions Firefox's sandbox
        // needs to initialize, which can hang or crash the browser without
        // this. Locally this is unnecessary and shows a security warning
        // banner, so it's gated behind common CI env vars rather than
        // applied unconditionally.
        boolean isCi = System.getenv("CI") != null
            || System.getenv("JENKINS_HOME") != null
            || System.getenv("GITLAB_CI") != null;
        if (isCi) {
            options.addPreference("security.sandbox.content.level", 0);
            options.addPreference("security.sandbox.gpu.level", 0);
            options.addPreference("security.sandbox.media.level", 0);
        }

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

        FirefoxDriver driver = new FirefoxDriver(options);

        // Firefox has no launch-time equivalent to Chrome/Edge's
        // --start-maximized argument, so the window opens at Firefox's
        // default size unless maximized after the fact.
        if (!headless) {
            driver.manage().window().maximize();
        }

        return driver;
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
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // 3. Try finding firefox on PATH via which/where command
        try {
            String whichCmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "where firefox" : "which firefox";
            Process p = Runtime.getRuntime().exec(whichCmd);
            String result = new String(p.getInputStream().readAllBytes()).trim();
            if (!result.isEmpty() && new File(result).exists()) {
                return result;
            }
        } catch (Exception ignored) {
            // fall through — geckodriver will search PATH itself
        }

        return null;  // geckodriver will attempt to find Firefox itself
    }

    // ── Edge ──────────────────────────────────────────────────────────────────

    private static WebDriver createEdgeDriver(boolean headless) {
        WebDriverManager.edgedriver().setup();

        EdgeOptions options = new EdgeOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
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
