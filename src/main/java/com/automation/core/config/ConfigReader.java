package com.automation.core.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * Loads configuration in three layers, each overriding the previous:
 *
 *   1. config/global.properties        - defaults shared by all sites
 *   2. config/{site}.properties         - site-specific overrides
 *   3. -Dkey=value JVM system properties - run-time overrides (Jenkins params)
 *
 * The active site is chosen with -Dsite=<siteName> (defaults to "demoqa").
 * Adding a new site project = drop a new config/<site>.properties file
 * next to demoqa.properties, no code changes required here.
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


    /**
     * Call this before switching to a different site in the same JVM.
     * Clears all loaded properties so the next ConfigReader.get() call
     * reloads config for the new site.
     */
    public static synchronized void reset() {
        properties.clear();
        initialized = false;
        activeSite  = null;
    }

    private static void loadFromClasspath(String path, boolean required) {
        try (InputStream input = ConfigReader.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                if (required) {
                    throw new RuntimeException("Required config file missing on classpath: " + path);
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
     * Returns the resolved value for a key, checking JVM system properties
     * first (so Jenkins/CLI can override anything), then the loaded
     * properties files.
     */
    public static String get(String key) {
        init();
        String systemOverride = System.getProperty(key);
        if (systemOverride != null && !systemOverride.isEmpty()) {
            return systemOverride;
        }
        if (!properties.containsKey(key)) {
            throw new RuntimeException("Missing config key: " + key);
        }
        return systemOverride;
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return (value != null) ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        try {
            return (value != null) ? Integer.parseInt(value.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return (value != null) ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    public static String getActiveSite() {
        init();
        return activeSite;
    }
}
