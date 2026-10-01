package com.automation.core.exceptions;

/**
 * Thrown by ConfigReader when a required config file or key is missing.
 * Retrying a test that hit this will fail identically every time — the
 * config problem doesn't fix itself — so this is a signal RetryListener
 * can use to skip wasted retry attempts if it's ever made exception-aware.
 */
public class ConfigException extends FrameworkException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
