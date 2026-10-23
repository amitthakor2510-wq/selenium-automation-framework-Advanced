package com.automation.sites.listeners;

import com.automation.core.exceptions.ConfigException;
import com.automation.core.exceptions.DataFileException;
import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link RetryAnalyzer#isDeterministicFailure} — no
 * TestNG ITestResult, no ConfigReader state, no browser.
 *
 * The regression these guard: KeywordEngine.run() wraps every step
 * exception (including transient Selenium ones) in a
 * KeywordExecutionException, and the analyzer used to treat any
 * KeywordExecutionException in the cause chain as "deterministic", so
 * flaky failures in keyword-driven tests were never retried.
 */
class RetryAnalyzerTest {

    @Test
    void configExceptionIsDeterministic() {
        assertTrue(RetryAnalyzer.isDeterministicFailure(new ConfigException("Missing config key: url")));
    }

    @Test
    void dataFileExceptionIsDeterministic() {
        assertTrue(RetryAnalyzer.isDeterministicFailure(new DataFileException("bad csv")));
    }

    @Test
    void frameworkRaisedKeywordExceptionIsDeterministic() {
        assertTrue(RetryAnalyzer.isDeterministicFailure(
            new KeywordExecutionException("[ObjectRepository] No locator for key 'x'")));
    }

    @Test
    void keywordExceptionWrappingNonSeleniumCauseIsDeterministic() {
        assertTrue(RetryAnalyzer.isDeterministicFailure(
            new KeywordExecutionException("Step failed", new IllegalStateException("bad row"))));
    }

    @Test
    void keywordExceptionWrappingTimeoutIsRetryable() {
        assertFalse(RetryAnalyzer.isDeterministicFailure(
            new KeywordExecutionException("Step failed", new TimeoutException("slow page"))));
    }

    @Test
    void keywordExceptionWrappingStaleElementIsRetryable() {
        assertFalse(RetryAnalyzer.isDeterministicFailure(
            new KeywordExecutionException("Step failed", new StaleElementReferenceException("stale"))));
    }

    @Test
    void keywordExceptionWrappingNoSuchElementIsRetryable() {
        assertFalse(RetryAnalyzer.isDeterministicFailure(
            new KeywordExecutionException("Step failed", new NoSuchElementException("gone"))));
    }

    @Test
    void configExceptionBuriedUnderBrowserExceptionIsStillDeterministic() {
        assertTrue(RetryAnalyzer.isDeterministicFailure(
            new TimeoutException("outer", new ConfigException("Missing config key: url"))));
    }

    @Test
    void plainAssertionFailureAndNullAreRetryable() {
        assertFalse(RetryAnalyzer.isDeterministicFailure(new AssertionError("expected 1 but was 2")));
        assertFalse(RetryAnalyzer.isDeterministicFailure(null));
    }
}
