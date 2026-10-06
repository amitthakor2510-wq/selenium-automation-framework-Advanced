package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Internationalisation (i18n) helper: looks up expected UI text, validation messages and test
 * data by key from per-language {@code .properties} bundles, so one test can run against the
 * English, Hindi, Gujarati ... build of an application without hard-coding any display string.
 *
 * <h3>Bundles</h3>
 * Files live on the classpath under the base name {@code i18n.bundle} (default
 * {@code i18n/messages}, i.e. {@code src/test/resources/i18n/}):
 * <pre>
 *   messages.properties      base/fallback language (English)
 *   messages_hi.properties   Hindi
 *   messages_gu.properties   Gujarati   (add any language the same way)
 * </pre>
 * Files are read as UTF-8, so write Hindi/Gujarati text directly - no unicode-escape sequences needed.
 *
 * <h3>Choosing the language</h3>
 * Resolution order: this thread's override ({@link #setLocale(String)}), then the JVM system
 * property {@code i18n.language} ({@code mvn test -Di18n.language=hi}), then
 * {@code i18n.language} in global.properties/site properties, then {@code en}. Accepts
 * {@code hi}, {@code hi-IN} or {@code hi_IN}.
 *
 * <h3>Fallback rules</h3>
 * A language with no bundle file (or a key missing from it) falls back to the BASE file only -
 * never to the JVM's default locale, which would make results depend on the machine running the
 * suite. A key missing from the base file as well throws {@link MissingResourceException}
 * naming the key, bundle and locale, rather than returning a placeholder that a test would then
 * silently assert against.
 *
 * <h3>Parallel runs</h3>
 * The per-thread override is a {@link ThreadLocal}; {@code BaseTest.tearDown()} calls
 * {@link #clearLocale()} so a language set by one test cannot leak into the next test that
 * reuses the same worker thread.
 *
 * <p>Messages that take arguments use {@link MessageFormat} ({@code Hello, {0}}); in those
 * messages a literal apostrophe must be doubled ({@code Don''t}). Messages looked up WITHOUT
 * arguments are returned verbatim, so a plain {@code Don't} is fine there.
 */
public final class LanguageUtils {

    private static final Logger logger = LoggerFactory.getLogger(LanguageUtils.class);

    /** Config / system-property key holding the active language tag. */
    public static final String LANGUAGE_KEY = "i18n.language";
    /** Config / system-property key holding the bundle base name. */
    public static final String BUNDLE_KEY = "i18n.bundle";

    static final String DEFAULT_LANGUAGE = "en";
    static final String DEFAULT_BUNDLE = "i18n/messages";

    private static final ThreadLocal<Locale> OVERRIDE = new ThreadLocal<>();
    private static final ConcurrentMap<String, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    private LanguageUtils() {
    }

    // ── Locale selection ─────────────────────────────────────────────────────

    /** The language in effect for the calling thread (see the class javadoc for the order). */
    public static Locale getLocale() {
        Locale override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        return parseLocale(configValue(LANGUAGE_KEY, DEFAULT_LANGUAGE));
    }

    /** Overrides the language for the CURRENT thread only, e.g. {@code setLocale("hi")}. */
    public static void setLocale(String languageTag) {
        setLocale(parseLocale(languageTag));
    }

    /** Overrides the language for the CURRENT thread only. */
    public static void setLocale(Locale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale must not be null (use clearLocale() to remove an override)");
        }
        OVERRIDE.set(locale);
    }

    /** Removes the current thread's override so the configured language applies again. */
    public static void clearLocale() {
        OVERRIDE.remove();
    }

    /** Drops every cached bundle (used by unit tests; a normal run never needs it). */
    public static void clearCache() {
        BUNDLES.clear();
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    /** The text for {@code key} in the active language. */
    public static String get(String key) {
        return lookup(getLocale(), key);
    }

    /** The text for {@code key} in the active language, with {@code {0}}, {@code {1}} ... filled in. */
    public static String get(String key, Object... args) {
        Locale locale = getLocale();
        String pattern = lookup(locale, key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, locale).format(args);
    }

    /** The text for {@code key} in an explicit language, ignoring the thread/config language. */
    public static String get(Locale locale, String key) {
        if (locale == null) {
            throw new IllegalArgumentException("locale must not be null");
        }
        return lookup(locale, key);
    }

    /** Like {@link #get(String)} but returns {@code defaultValue} instead of throwing when the key is absent. */
    public static String getOrDefault(String key, String defaultValue) {
        try {
            return lookup(getLocale(), key);
        } catch (MissingResourceException e) {
            return defaultValue;
        }
    }

    /** True when {@code key} resolves in the active language (own file or the base file). */
    public static boolean has(String key) {
        return getOrDefault(key, null) != null;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String lookup(Locale locale, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("i18n key must not be null or blank");
        }
        String base = baseName();
        ResourceBundle bundle = bundleFor(base, locale);
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            throw new MissingResourceException("No i18n text for key '" + key + "' in bundle '" + base
                + "' (locale " + locale.toLanguageTag() + ", incl. base file)", base, key);
        }
    }

    private static ResourceBundle bundleFor(String base, Locale locale) {
        return BUNDLES.computeIfAbsent(base + "|" + locale.toLanguageTag(), k -> {
            try {
                // NoFallback: an unknown language resolves to the base file, not to whatever the
                // JVM's default locale happens to be on this machine.
                return ResourceBundle.getBundle(base, locale, LanguageUtils.class.getClassLoader(),
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            } catch (MissingResourceException e) {
                throw new MissingResourceException("i18n bundle '" + base + "' not found on the classpath "
                    + "(expected " + base + ".properties, e.g. under src/test/resources) - set "
                    + BUNDLE_KEY + " if it lives elsewhere", base, "");
            }
        });
    }

    private static String baseName() {
        String name = configValue(BUNDLE_KEY, DEFAULT_BUNDLE).trim();
        return name.isEmpty() ? DEFAULT_BUNDLE : name;
    }

    /** System property first, then ConfigReader; never throws so this stays usable in plain unit tests. */
    private static String configValue(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        try {
            String cfg = ConfigReader.get(key, defaultValue);
            return cfg == null || cfg.isBlank() ? defaultValue : cfg;
        } catch (RuntimeException e) {
            logger.debug("[LanguageUtils] Config not readable for '{}', using default '{}': {}",
                key, defaultValue, e.getMessage());
            return defaultValue;
        }
    }

    static Locale parseLocale(String tag) {
        if (tag == null || tag.isBlank()) {
            return Locale.forLanguageTag(DEFAULT_LANGUAGE);
        }
        Locale locale = Locale.forLanguageTag(tag.trim().replace('_', '-'));
        if (locale.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("Not a valid language tag: '" + tag + "' (expected e.g. en, hi, hi-IN)");
        }
        return locale;
    }
}
