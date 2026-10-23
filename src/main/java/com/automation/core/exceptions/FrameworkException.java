package com.automation.core.exceptions;

/**
 * Base type for exceptions thrown by the framework's own infrastructure
 * (config loading, driver setup, keyword engine, etc.) as opposed to a
 * test's own assertion failures.
 *
 * The distinction matters for RetryListener/RetryAnalyzer: a test that
 * failed because an assertion didn't hold is a different situation from
 * one that failed because Chrome never launched or a required config key
 * was missing — the latter usually shouldn't burn through retry.count
 * attempts identically to a flaky assertion, since retrying won't fix a
 * missing config file. Subclass this rather than throwing a raw
 * RuntimeException from framework code, so that distinction is visible
 * to anything catching by type (listeners, reporting, retry logic).
 *
 * Still an unchecked exception on purpose — framework setup failures are
 * not something call sites are expected to recover from inline, only to
 * report clearly and (for tests) fail fast.
 */
public class FrameworkException extends RuntimeException {

    public FrameworkException(String message) {
        super(message);
    }

    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
