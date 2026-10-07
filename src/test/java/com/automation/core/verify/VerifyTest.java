package com.automation.core.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testng.Assert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free unit tests for {@link Verify} and {@link FailureHandling}. */
class VerifyTest {

    @AfterEach
    void clean() {
        Verify.drain();
        System.clearProperty(Verify.DEFAULT_HANDLING_PROPERTY);
    }

    @Test
    void passingCheckReturnsTrueAndIsCounted() {
        assertTrue(Verify.verifyEquals("a", "a", "same", FailureHandling.CONTINUE_ON_FAILURE));

        Verify.Outcome outcome = Verify.drain();
        assertEquals(1, outcome.passed());
        assertTrue(outcome.failures().isEmpty());
        assertTrue(outcome.warnings().isEmpty());
    }

    @Test
    void stopOnFailureThrowsAndCollectsNothing() {
        assertThrows(AssertionError.class,
            () -> Verify.verifyTrue(false, "must hold", FailureHandling.STOP_ON_FAILURE));

        Verify.Outcome outcome = Verify.drain();
        assertTrue(outcome.failures().isEmpty());
        assertTrue(outcome.warnings().isEmpty());
    }

    @Test
    void continueOnFailureCollectsAndKeepsGoing() {
        assertFalse(Verify.verifyEquals("x", "y", "first", FailureHandling.CONTINUE_ON_FAILURE));
        assertFalse(Verify.verifyNotNull(null, "second", FailureHandling.CONTINUE_ON_FAILURE));
        assertTrue(Verify.verifyTrue(true, "third", FailureHandling.CONTINUE_ON_FAILURE));

        Verify.Outcome outcome = Verify.drain();
        assertEquals(2, outcome.failures().size());
        assertEquals(1, outcome.passed());
        assertEquals("first", outcome.failures().get(0).description());
        assertEquals(FailureHandling.CONTINUE_ON_FAILURE, outcome.failures().get(1).handling());
    }

    @Test
    void optionalFailureIsOnlyAWarning() {
        assertFalse(Verify.verifyFalse(true, "nice to have", FailureHandling.OPTIONAL));

        Verify.Outcome outcome = Verify.drain();
        assertTrue(outcome.failures().isEmpty());
        assertEquals(1, outcome.warnings().size());
    }

    @Test
    void drainClearsEverything() {
        Verify.verifyTrue(false, "x", FailureHandling.CONTINUE_ON_FAILURE);
        Verify.verifyTrue(false, "y", FailureHandling.OPTIONAL);
        Verify.drain();

        Verify.Outcome second = Verify.drain();
        assertTrue(second.failures().isEmpty());
        assertTrue(second.warnings().isEmpty());
        assertEquals(0, second.passed());
    }

    @Test
    void checkWrapsAnyExistingAssertCall() {
        boolean passed = Verify.check("cart total", FailureHandling.CONTINUE_ON_FAILURE,
            () -> Assert.assertEquals(41.0, 42.0, 0.001));

        assertFalse(passed);
        assertEquals(1, Verify.drain().failures().size());
    }

    @Test
    void nonAssertionExceptionsStillPropagate() {
        assertThrows(IllegalStateException.class, () -> Verify.check("boom", FailureHandling.CONTINUE_ON_FAILURE,
            () -> {
                throw new IllegalStateException("not an assertion");
            }));
        assertTrue(Verify.drain().failures().isEmpty());
    }

    @Test
    void verifyContainsHandlesNullAndMissingSubstring() {
        assertTrue(Verify.verifyContains("hello world", "world", "has world"));
        assertFalse(Verify.verifyContains("hello", "world", "has world", FailureHandling.CONTINUE_ON_FAILURE));
        assertFalse(Verify.verifyContains(null, "world", "null text", FailureHandling.CONTINUE_ON_FAILURE));
        assertEquals(2, Verify.drain().failures().size());
    }

    @Test
    void failureMessageCarriesDescriptionAndAssertionText() {
        Verify.verifyEquals("a", "b", "page title", FailureHandling.CONTINUE_ON_FAILURE);

        String message = Verify.drain().failures().get(0).message();
        assertTrue(message.contains("page title"), message);
        assertTrue(message.contains("expected"), message);
    }

    @Test
    void verificationErrorListsEveryFailureAndKeepsTheirTraces() {
        Verify.verifyTrue(false, "one", FailureHandling.CONTINUE_ON_FAILURE);
        Verify.verifyTrue(false, "two", FailureHandling.CONTINUE_ON_FAILURE);

        VerificationError error = new VerificationError(Verify.drain().failures());

        assertTrue(error.getMessage().startsWith("2 verification(s) failed:"), error.getMessage());
        assertTrue(error.getMessage().contains("1) one"), error.getMessage());
        assertTrue(error.getMessage().contains("2) two"), error.getMessage());
        assertEquals(2, error.getSuppressed().length);
    }

    @Test
    void noModeUsesTheConfiguredDefault() {
        System.setProperty(Verify.DEFAULT_HANDLING_PROPERTY, "continue_on_failure");

        assertEquals(FailureHandling.CONTINUE_ON_FAILURE, Verify.defaultHandling());
        assertFalse(Verify.verifyTrue(false, "default mode applies"));
        assertEquals(1, Verify.drain().failures().size());
    }

    @Test
    void unknownDefaultFallsBackToStopOnFailure() {
        System.setProperty(Verify.DEFAULT_HANDLING_PROPERTY, "sometimes");

        assertEquals(FailureHandling.STOP_ON_FAILURE, Verify.defaultHandling());
        assertThrows(AssertionError.class, () -> Verify.verifyTrue(false, "stops"));
    }

    @Test
    void parseIsCaseInsensitiveAndFallsBackOnBlank() {
        assertEquals(FailureHandling.OPTIONAL, FailureHandling.parse("  Optional ", FailureHandling.STOP_ON_FAILURE));
        assertEquals(FailureHandling.OPTIONAL, FailureHandling.parse(null, FailureHandling.OPTIONAL));
        assertEquals(FailureHandling.OPTIONAL, FailureHandling.parse("  ", FailureHandling.OPTIONAL));
    }
}
