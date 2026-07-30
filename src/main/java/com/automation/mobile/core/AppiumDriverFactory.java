package com.automation.mobile.core;

import com.automation.core.config.ConfigReader;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Mobile counterpart to core.driver.DriverFactory — same "one place creates
 * the driver" pattern, retargeted at Appium instead of a local/grid browser.
 *
 * THIS IS A SCAFFOLD, not a finished module: it compiles and follows this
 * framework's conventions (ConfigReader-driven config, single factory
 * method, capability building split out for readability), but it has not
 * been run against a real device/emulator or a real .apk/.ipa, since none
 * were available at the time this was added. Before first use:
 *
 *   1. Start an Appium server (npm install -g appium && appium), or point
 *      appium.server.url at a remote/cloud grid (BrowserStack, Sauce Labs,
 *      a self-hosted Appium grid, etc.)
 *   2. Set mobile.app.path to a real .apk (Android) or .ipa/.app (iOS) —
 *      see src/test/resources/config/mobile.properties.example
 *   3. For a real device (as opposed to an emulator/simulator), set
 *      mobile.device.name / mobile.platform.version to match it.
 *
 * Natural fit for the government Android apps already covered by manual
 * testing (PM Gati Shakti, ministry portals, etc.) — wrap each app's screens
 * as Page Objects under com.automation.mobile.sites.<app>.pages the same
 * way com.automation.sites.demoqa.pages does for web.
 */
public final class AppiumDriverFactory {

    private static final Logger logger = Logger.getLogger(AppiumDriverFactory.class.getName());

    private AppiumDriverFactory() {
    }

    public static RemoteWebDriver createDriver() {
        String platform = ConfigReader.get("mobile.platform", "android").toLowerCase();
        String serverUrl = ConfigReader.get("appium.server.url", "http://127.0.0.1:4723");

        return switch (platform) {
            case "android" -> createAndroidDriver(serverUrl);
            case "ios" -> createIosDriver(serverUrl);
            default -> throw new RuntimeException("[AppiumDriverFactory] mobile.platform not supported: "
                + platform + ". Supported: android, ios");
        };
    }

    private static AndroidDriver createAndroidDriver(String serverUrl) {
        UiAutomator2Options options = new UiAutomator2Options();
        options.setDeviceName(ConfigReader.get("mobile.device.name", "emulator-5554"));
        options.setAutomationName("UiAutomator2");

        String appPath = ConfigReader.get("mobile.app.path", "");
        if (!appPath.isEmpty()) {
            options.setApp(new File(appPath).getAbsolutePath());
        } else {
            // Testing an already-installed app by package/activity instead of
            // installing a fresh .apk — set these instead of mobile.app.path.
            String appPackage = ConfigReader.get("mobile.app.package", "");
            String appActivity = ConfigReader.get("mobile.app.activity", "");
            if (!appPackage.isEmpty()) {
                options.setAppPackage(appPackage);
            }
            if (!appActivity.isEmpty()) {
                options.setAppActivity(appActivity);
            }
            if (appPackage.isEmpty() && appActivity.isEmpty()) {
                // Appium itself only logs this fact deep in its own server
                // log ("Neither 'app' nor 'appPackage' was set..."), which is
                // easy to miss — the session still starts "successfully",
                // just sitting on whatever screen the device happened to be
                // on (usually the home launcher), so every element lookup in
                // the test then fails with a confusing NoSuchElementError
                // that looks unrelated to configuration. Surface it here,
                // in our own log, right where the driver is being built.
                logger.warning("[AppiumDriverFactory] No mobile.app.path, mobile.app.package, or "
                    + "mobile.app.activity set — the session will start with NO target app "
                    + "(landing on whatever screen the device is already on, typically the home "
                    + "launcher). If your test expects a specific app/screen, set "
                    + "mobile.app.path (fresh install) or mobile.app.package + mobile.app.activity "
                    + "(already-installed app) in mobile.properties or via -D overrides.");
            }
        }

        String platformVersion = ConfigReader.get("mobile.platform.version", "");
        if (!platformVersion.isEmpty()) {
            options.setPlatformVersion(platformVersion);
        }

        options.setNewCommandTimeout(Duration.ofSeconds(ConfigReader.getInt("mobile.newCommandTimeout", 120)));
        options.setNoReset(ConfigReader.getBoolean("mobile.noReset", false));

        return new AndroidDriver(toUrl(serverUrl), options);
    }

    private static IOSDriver createIosDriver(String serverUrl) {
        XCUITestOptions options = new XCUITestOptions();
        options.setDeviceName(ConfigReader.get("mobile.device.name", "iPhone Simulator"));

        String appPath = ConfigReader.get("mobile.app.path", "");
        if (!appPath.isEmpty()) {
            options.setApp(new File(appPath).getAbsolutePath());
        }

        String platformVersion = ConfigReader.get("mobile.platform.version", "");
        if (!platformVersion.isEmpty()) {
            options.setPlatformVersion(platformVersion);
        }

        options.setNewCommandTimeout(Duration.ofSeconds(ConfigReader.getInt("mobile.newCommandTimeout", 120)));
        options.setNoReset(ConfigReader.getBoolean("mobile.noReset", false));

        return new IOSDriver(toUrl(serverUrl), options);
    }

    private static URL toUrl(String serverUrl) {
        try {
            return URI.create(serverUrl).toURL();
        } catch (Exception e) {
            throw new RuntimeException("[AppiumDriverFactory] Invalid appium.server.url: " + serverUrl, e);
        }
    }
}
