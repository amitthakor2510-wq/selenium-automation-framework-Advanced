package com.automation.core.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * Loads configuration in three layers, each overriding the previous:
 *
 *   1. config/global.properties        - defaults shared by all sites
 *   2. config/{site}.properties         - site-specific overrides
 *   3. -Dkey=value JVM system properties - run-time overrides (Jenkins/CLI)
 *
 * The active site is chosen with -Dsite=<siteName> (defaults to "demoqa").
 */
public class ConfigReader {

    private static final Properties properties = new Properties();
    private static volatile boolean initialized = false;
    private static String activeSite;
    private static final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ConfigReader.class.getName());

    private ConfigReader() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }

        activeSite = System.getProperty("site", "demoqa");
        loadFromClasspath("config/global.properties", true);
        loadFromClasspath("config/" + activeSite + ".properties", false);
        initialized = true;
    }

    public static synchronized void reset() {
        properties.clear();
        initialized = false;
        activeSite = null;
    }

    private static void loadFromClasspath(String path, boolean required) {
        try (InputStream input = ConfigReader.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                if (required) {
                    throw new RuntimeException("Required config file missing: " + path);
                }
                logger.info("[ConfigReader] Optional config not found: " + path);
                return;
            }
            properties.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config file: " + path, e);
        }
    }

    /**
     * Resolve a key: system property wins, then properties file, then throws.
     * Use this only when the key is guaranteed to exist (e.g. "url").
     */
    public static synchronized String get(String key) {
        init();
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        String val = properties.getProperty(key);
        if (val == null) {
            throw new RuntimeException("Missing config key: " + key);
        }
        return val;
    }

    /**
     * Resolve a key with a fallback default.
     * System property wins → properties file → defaultValue.
     * Never throws.
     */
    public static synchronized String get(String key, String defaultValue) {
        init();
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Integer helper. Falls back to defaultValue if key is absent or not a number.
     */
    public static int getInt(String key, int defaultValue) {
        String value = get(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Boolean helper. Falls back to defaultValue if key is absent.
     * -Dheadless=false or headless=false in .properties both work correctly.
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value.trim());
    }

    public static synchronized String getActiveSite() {
        init();
        return activeSite;
    }
}
