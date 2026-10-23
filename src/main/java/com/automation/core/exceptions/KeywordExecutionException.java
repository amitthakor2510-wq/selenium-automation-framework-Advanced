package com.automation.core.exceptions;

/**
 * Thrown by the keyword-driven engine (KeywordEngine, KeywordReader,
 * KeywordStep, ObjectRepository) when a keyword-driven test can't
 * proceed — an unknown keyword, a missing locator, a malformed step row,
 * an unresolved test-data key, etc.
 *
 * These are almost always authoring mistakes in a keyword test-data file
 * (a typo'd keyword name, a locator key that doesn't exist in the object
 * repository) rather than environment flakiness — same failure on every
 * retry — which is why RetryAnalyzer treats this the same way it treats
 * ConfigException.
 */
public class KeywordExecutionException extends FrameworkException {

    public KeywordExecutionException(String message) {
        super(message);
    }

    public KeywordExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
