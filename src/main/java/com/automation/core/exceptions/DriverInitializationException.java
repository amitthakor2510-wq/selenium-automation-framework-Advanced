package com.automation.core.exceptions;

/**
 * Thrown by DriverFactory when a browser/driver session can't be created —
 * unsupported browser name, missing binary (e.g. Brave not installed),
 * malformed grid.url, etc.
 *
 * Unlike a flaky element-not-found failure, most of these are also not
 * fixed by retrying (a missing Brave binary is still missing on attempt
 * 2) — some (a Grid node being briefly unreachable) plausibly are. Kept
 * as a distinct type from ConfigException so future retry logic can
 * treat them differently if that distinction turns out to matter.
 */
public class DriverInitializationException extends FrameworkException {

    public DriverInitializationException(String message) {
        super(message);
    }

    public DriverInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
