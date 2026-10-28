package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** WebDriver-free unit tests for the WAIT_SECONDS value check in {@link KeywordEngine}. */
class KeywordWaitSecondsTest {

    @Test
    void acceptsWholeAndFractionalSeconds() {
        assertEquals(2000, KeywordEngine.parseWaitMillis("2"));
        assertEquals(1500, KeywordEngine.parseWaitMillis(" 1.5 "));
        assertEquals(0, KeywordEngine.parseWaitMillis("0"));
        assertEquals(300_000, KeywordEngine.parseWaitMillis("300"));
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("-1"));
    }

    @Test
    void rejectsNaNAndInfinity() {
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("NaN"));
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("Infinity"));
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("-Infinity"));
    }

    @Test
    void rejectsValuesAboveTheCap() {
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("300.001"));
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("6000000"));
    }

    @Test
    void rejectsMissingOrNonNumericText() {
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis(null));
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis(""));
        assertThrows(KeywordExecutionException.class, () -> KeywordEngine.parseWaitMillis("soon"));
    }
}
