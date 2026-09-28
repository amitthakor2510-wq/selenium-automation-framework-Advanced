package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free unit tests for {@link PlaceholderResolver} (JUnit 5, opt-in like the other data tests). */
class PlaceholderResolverTest {

    private static final String PROP = "placeholder.test.value";

    @AfterEach
    void clear() {
        System.clearProperty(PROP);
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("Test@1234", PlaceholderResolver.resolve("Test@1234"));
        assertEquals("", PlaceholderResolver.resolve(null));
        assertFalse(PlaceholderResolver.containsPlaceholder("plain"));
    }

    @Test
    void envPlaceholderFallsBackToSystemProperty() {
        System.setProperty(PROP, "from-sysprop");
        assertEquals("user=from-sysprop!", PlaceholderResolver.resolve("user=${env:" + PROP + "}!"));
        assertTrue(PlaceholderResolver.containsPlaceholder("${env:" + PROP + "}"));
    }

    @Test
    void sysPlaceholderReadsSystemProperty() {
        System.setProperty(PROP, "abc");
        assertEquals("abc-abc", PlaceholderResolver.resolve("${sys:" + PROP + "}-${sys:" + PROP + "}"));
    }

    @Test
    void inlineDefaultUsedOnlyWhenNoValue() {
        assertEquals("fallback", PlaceholderResolver.resolve("${env:" + PROP + ":-fallback}"));
        assertEquals("", PlaceholderResolver.resolve("${env:" + PROP + ":-}"));
        System.setProperty(PROP, "real");
        assertEquals("real", PlaceholderResolver.resolve("${env:" + PROP + ":-fallback}"));
    }

    @Test
    void missingValueWithoutDefaultFailsWithVariableName() {
        KeywordExecutionException e = assertThrows(KeywordExecutionException.class,
            () -> PlaceholderResolver.resolve("${env:" + PROP + "}"));
        assertTrue(e.getMessage().contains(PROP), e.getMessage());
    }

    @Test
    void unrecognisedPrefixesAreLeftAlone() {
        String payload = "${jndi:ldap://x/a}";
        assertEquals(payload, PlaceholderResolver.resolve(payload));
        assertFalse(PlaceholderResolver.containsPlaceholder(payload));
    }
}
