package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free tests for script variables and the {@code ${var:name}} placeholder. */
class KeywordVariablesTest {

    @AfterEach
    void clear() {
        KeywordVariables.clear();
    }

    @Test
    void storedValuesAreReadBackAndClearedOnDemand() {
        KeywordVariables.put("order.id", "A-77");
        assertEquals("A-77", KeywordVariables.get("order.id"));
        KeywordVariables.clear();
        assertNull(KeywordVariables.get("order.id"));
    }

    @Test
    void aNullValueIsStoredAsEmptyAndBadNamesAreRejected() {
        KeywordVariables.put("empty", null);
        assertEquals("", KeywordVariables.get("empty"));
        assertThrows(KeywordExecutionException.class, () -> KeywordVariables.put("has space", "x"));
        assertThrows(KeywordExecutionException.class, () -> KeywordVariables.put("", "x"));
    }

    @Test
    void theVarPlaceholderResolvesAndAnUnsetOneExplainsWhere() {
        KeywordVariables.put("id", "42");
        assertEquals("order-42-ok", PlaceholderResolver.resolve("order-${var:id}-ok"));
        assertEquals("fallback", PlaceholderResolver.resolve("${var:missing:-fallback}"));
        KeywordExecutionException e = assertThrows(KeywordExecutionException.class,
            () -> PlaceholderResolver.resolve("${var:missing}"));
        assertTrue(e.getMessage().contains("STORE_TEXT"), e.getMessage());
    }

    @Test
    void aNewEngineStartsWithNoLeftoverVariables() {
        KeywordVariables.put("leak", "x");
        new KeywordEngine(null, null);
        assertNull(KeywordVariables.get("leak"));
    }
}
