package com.automation.core.exceptions;

/**
 * Thrown when a test-data file (CSV/Excel/JSON/YAML/ZIP) can't be read or
 * parsed — missing file, malformed content, unregistered file extension,
 * a DataRow column that doesn't exist, etc.
 *
 * Like KeywordExecutionException, this is almost always a data-authoring
 * problem rather than environment flakiness, so RetryAnalyzer treats it
 * as non-retryable too — a malformed CSV is still malformed on attempt 2.
 */
public class DataFileException extends FrameworkException {

    public DataFileException(String message) {
        super(message);
    }

    public DataFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
