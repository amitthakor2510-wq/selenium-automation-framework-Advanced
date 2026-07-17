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
 * Supports chrome / firefox / edge, and a headless=true/false
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
            default:
                throw new RuntimeException("Browser not supported: " + browser);
        }
    }

    private static WebDriver createChromeDriver(boolean headless) {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        // Download directory — matches what UploadDownloadTest uses
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

        return new ChromeDriver(options);
    }

    private static WebDriver createFirefoxDriver(boolean headless) {
        String geckodriverPath = System.getProperty("user.home")
                + "/Downloads/geckodriver-v0.36.0-linux64/geckodriver";

        File geckodriverFile = new File(geckodriverPath);
        if (geckodriverFile.exists()) {
            System.setProperty("webdriver.gecko.driver", geckodriverPath);
            System.out.println("[DriverFactory] Using cached geckodriver: " + geckodriverPath);
        } else {
            WebDriverManager.firefoxdriver().setup();
            System.out.println("[DriverFactory] Cached geckodriver not found, falling back to WDM");
        }

        // disable sandbox — required on Ubuntu with Firefox 152 ESR
        System.setProperty("MOZ_DISABLE_CONTENT_SANDBOX", "1");

        FirefoxOptions options = new FirefoxOptions();
        options.setBinary("/usr/lib/firefox/firefox");

        // pass sandbox disable as environment variable to Firefox process
        options.addPreference("security.sandbox.content.level", 0);
        options.addPreference("security.sandbox.gpu.level", 0);
        options.addPreference("security.sandbox.media.level", 0);

        options.addPreference("browser.download.folderList", 2);
        options.addPreference("browser.download.dir", getDownloadPath());
        options.addPreference("browser.helperApps.neverAsk.saveToDisk",
                "application/octet-stream");

        if (headless) {
            options.addArguments("-headless");
            options.addArguments("--width=1920");
            options.addArguments("--height=1080");
        }

        return new FirefoxDriver(options);
    }

    private static WebDriver createEdgeDriver(boolean headless) {
        WebDriverManager.edgedriver().setup();

        EdgeOptions options = new EdgeOptions();
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        // Same download preferences as Chrome
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

    private static String getDownloadPath() {
        String path = System.getProperty("user.dir")
                + File.separator + "target"
                + File.separator + "downloads";
        new File(path).mkdirs();
        return path;
    }
}
