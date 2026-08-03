package com.automation.core.config;

import com.automation.core.exceptions.ConfigException;

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
 * Before any config is loaded, SiteRegistry.validate(site) checks the site
 * is registered and has every resource it requires (config file, and an
 * object repository if it runs keyword-driven tests) — so a missing piece
 * fails immediately with a specific message instead of surfacing later as
 * a confusing NPE deep in a page object or the keyword engine.
 *
 * State is held per-thread (ThreadLocal), not as shared statics. Previously
 * a single shared Properties object meant one thread calling reset() (e.g.
 * in an @AfterMethod) could wipe config out from under another thread still
 * mid-test in TestNG parallel="methods"/"classes" runs — a real race
 * condition. Each thread now owns its own config lifecycle independently.
 *
 * Note: -Dsite is still a single JVM-wide system property, so it can't
 * differ per thread on its own. If per-thread site selection is ever
 * needed (e.g. demoqa and saucedemo suites in the same parallel run), add
 * a setActiveSite(String) that writes to activeSiteTL directly instead of
 * reading System.getProperty("site").
 */
public class ConfigReader {

    private static final ThreadLocal<Properties> propertiesTL =
        ThreadLocal.withInitial(Properties::new);
    private static final ThreadLocal<Boolean> initializedTL =
        ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> activeSiteTL = new ThreadLocal<>();
    private static final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ConfigReader.class.getName());

    private ConfigReader() {
    }

    public static void init() {
        if (initializedTL.get()) {
            return;
        }

        String site = System.getProperty("site", "demoqa");
        activeSiteTL.set(site);
        SiteRegistry.validate(site);
        loadFromClasspath("config/global.properties", true);
        loadFromClasspath("config/" + site + ".properties", false);
        initializedTL.set(true);
    }

    /**
     * Clears this thread's config state only. Safe to call from any test
     * thread without affecting config already loaded on other threads.
     */
    public static void reset() {
        propertiesTL.get().clear();
        initializedTL.set(false);
        activeSiteTL.remove();
    }

    private static void loadFromClasspath(String path, boolean required) {
        try (InputStream input = ConfigReader.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                if (required) {
                    throw new ConfigException("Required config file missing: " + path);
                }
                logger.info("[ConfigReader] Optional config not found: " + path);
                return;
            }
            propertiesTL.get().load(input);
        } catch (java.io.IOException e) {
            throw new ConfigException("Failed to load config file: " + path, e);
        }
    }

    /**
     * Resolve a key: system property wins, then properties file, then throws.
     * Use this only when the key is guaranteed to exist (e.g. "url").
     */
    public static String get(String key) {
        init();
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        String val = propertiesTL.get().getProperty(key);
        if (val == null) {
            throw new ConfigException("Missing config key: " + key);
        }
        return val;
    }

    /**
     * Resolve a key with a fallback default.
     * System property wins → properties file → defaultValue.
     * Never throws.
     */
    public static String get(String key, String defaultValue) {
        init();
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        return propertiesTL.get().getProperty(key, defaultValue);
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

    public static String getActiveSite() {
        init();
        return activeSiteTL.get();
    }
}
