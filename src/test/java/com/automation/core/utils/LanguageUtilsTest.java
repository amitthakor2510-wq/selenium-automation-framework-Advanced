package com.automation.core.utils;

import com.automation.core.keyword.PlaceholderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free unit tests for {@link LanguageUtils} (JUnit 5, run via {@code -Punit-tests}). */
class LanguageUtilsTest {

    private static final String FIXTURE = "i18n/lang-utils-test";

    private Locale originalDefault;

    @BeforeEach
    void setUp() {
        originalDefault = Locale.getDefault();
        System.clearProperty(LanguageUtils.LANGUAGE_KEY);
        System.clearProperty(LanguageUtils.BUNDLE_KEY);
        LanguageUtils.clearLocale();
        LanguageUtils.clearCache();
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalDefault);
        System.clearProperty(LanguageUtils.LANGUAGE_KEY);
        System.clearProperty(LanguageUtils.BUNDLE_KEY);
        LanguageUtils.clearLocale();
        LanguageUtils.clearCache();
    }

    @Test
    void defaultsToTheEnglishBaseBundle() {
        assertEquals("Login", LanguageUtils.get("login.title"));
        assertEquals("en", LanguageUtils.getLocale().getLanguage());
    }

    @Test
    void threadOverrideSelectsAnotherLanguage() {
        LanguageUtils.setLocale("hi");
        assertEquals("लॉगिन", LanguageUtils.get("login.title"));
        LanguageUtils.setLocale("gu");
        assertEquals("લૉગિન", LanguageUtils.get("login.title"));
        LanguageUtils.clearLocale();
        assertEquals("Login", LanguageUtils.get("login.title"));
    }

    @Test
    void systemPropertySelectsLanguageAndAcceptsUnderscoreRegionTags() {
        System.setProperty(LanguageUtils.LANGUAGE_KEY, "hi_IN");
        assertEquals("hi", LanguageUtils.getLocale().getLanguage());
        assertEquals("IN", LanguageUtils.getLocale().getCountry());
        assertEquals("लॉगिन", LanguageUtils.get("login.title"));
    }

    @Test
    void threadOverrideBeatsSystemProperty() {
        System.setProperty(LanguageUtils.LANGUAGE_KEY, "hi");
        LanguageUtils.setLocale("gu");
        assertEquals("લૉગિન", LanguageUtils.get("login.title"));
    }

    @Test
    void unknownLanguageFallsBackToBaseNotToTheJvmDefaultLocale() {
        // If the JVM default were honoured, "fr" (no file) would pick up the Hindi file here.
        Locale.setDefault(Locale.forLanguageTag("hi"));
        assertEquals("Login", LanguageUtils.get(Locale.forLanguageTag("fr"), "login.title"));
    }

    @Test
    void partialTranslationFallsBackToBaseForMissingKeys() {
        System.setProperty(LanguageUtils.BUNDLE_KEY, FIXTURE);
        LanguageUtils.setLocale("hi");
        assertEquals("नमस्ते", LanguageUtils.get("greeting"));
        assertEquals("base only", LanguageUtils.get("only.base"));
    }

    @Test
    void argumentsAreFilledInWithTheActiveLanguage() {
        assertEquals("Welcome, Amit!", LanguageUtils.get("common.welcome", "Amit"));
        LanguageUtils.setLocale("hi");
        assertEquals("स्वागत है, Amit!", LanguageUtils.get("common.welcome", "Amit"));
    }

    @Test
    void apostropheIsVerbatimWithoutArgsAndDoubledWithArgs() {
        System.setProperty(LanguageUtils.BUNDLE_KEY, FIXTURE);
        assertEquals("Don't stop", LanguageUtils.get("quote.plain"));
        assertEquals("Don't stop now", LanguageUtils.get("quote.formatted", "now"));
    }

    @Test
    void missingKeyThrowsWithAHelpfulMessage() {
        MissingResourceException e = assertThrows(MissingResourceException.class,
            () -> LanguageUtils.get("no.such.key"));
        assertTrue(e.getMessage().contains("no.such.key"), e.getMessage());
        assertEquals("no.such.key", e.getKey());
    }

    @Test
    void getOrDefaultAndHasDoNotThrow() {
        assertEquals("fallback", LanguageUtils.getOrDefault("no.such.key", "fallback"));
        assertNull(LanguageUtils.getOrDefault("no.such.key", null));
        assertTrue(LanguageUtils.has("login.title"));
        assertFalse(LanguageUtils.has("no.such.key"));
    }

    @Test
    void missingBundleFailsWithAnActionableMessage() {
        System.setProperty(LanguageUtils.BUNDLE_KEY, "i18n/does-not-exist");
        MissingResourceException e = assertThrows(MissingResourceException.class,
            () -> LanguageUtils.get("login.title"));
        assertTrue(e.getMessage().contains(LanguageUtils.BUNDLE_KEY), e.getMessage());
    }

    @Test
    void blankKeyAndBadLanguageTagAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LanguageUtils.get(" "));
        assertThrows(IllegalArgumentException.class, () -> LanguageUtils.get((String) null));
        assertThrows(IllegalArgumentException.class, () -> LanguageUtils.setLocale("###"));
        assertThrows(IllegalArgumentException.class, () -> LanguageUtils.setLocale((Locale) null));
    }

    @Test
    void overrideIsPerThread() throws Exception {
        LanguageUtils.setLocale("hi");
        String[] other = new String[1];
        Thread t = new Thread(() -> other[0] = LanguageUtils.get("login.title"));
        t.start();
        t.join();
        assertEquals("Login", other[0]);
        assertEquals("लॉगिन", LanguageUtils.get("login.title"));
    }

    @Test
    void keyDrivenPlaceholderResolvesThroughTheKeywordResolver() {
        LanguageUtils.setLocale("hi");
        assertEquals("लॉगिन करें", PlaceholderResolver.resolve("${i18n:login.button}"));
        assertEquals("x-fallback", PlaceholderResolver.resolve("${i18n:no.such.key:-x-fallback}"));
        assertTrue(PlaceholderResolver.containsPlaceholder("${i18n:login.title}"));
    }

    /** Guards the shipped sample bundles: a key added to one language but forgotten in another. */
    @Test
    void shippedLanguagesHaveExactlyTheBaseKeys() throws IOException {
        Set<String> base = keysOf("messages.properties");
        assertFalse(base.isEmpty());
        for (String lang : new String[] {"hi", "gu"}) {
            Set<String> other = keysOf("messages_" + lang + ".properties");
            Set<String> missing = new HashSet<>(base);
            missing.removeAll(other);
            Set<String> extra = new HashSet<>(other);
            extra.removeAll(base);
            assertTrue(missing.isEmpty(), lang + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), lang + " has keys not in the base file: " + extra);
        }
    }

    /** Reads one shipped bundle file directly (UTF-8) so only its OWN keys are returned. */
    private static Set<String> keysOf(String fileName) throws IOException {
        String path = "i18n/" + fileName;
        try (InputStream in = LanguageUtilsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing bundle file on the classpath: " + path);
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props.stringPropertyNames();
        }
    }
}
