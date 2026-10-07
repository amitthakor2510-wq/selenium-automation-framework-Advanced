package com.automation.core.verify;

import java.util.Locale;

/**
 * What a {@link Verify} check does when it fails. Same three modes as Katalon's
 * {@code FailureHandling}, so tests ported from there keep their meaning.
 *
 * <table>
 *   <caption>Behaviour per mode</caption>
 *   <tr><th>Mode</th><th>Test continues?</th><th>Test result</th></tr>
 *   <tr><td>{@link #STOP_ON_FAILURE}</td><td>No - throws immediately</td><td>FAILED</td></tr>
 *   <tr><td>{@link #CONTINUE_ON_FAILURE}</td><td>Yes</td><td>FAILED at the end, with every
 *       collected failure listed</td></tr>
 *   <tr><td>{@link #OPTIONAL}</td><td>Yes</td><td>Unaffected - logged as a warning only</td></tr>
 * </table>
 */
public enum FailureHandling {

    /** Hard assert: the first failure throws and ends the test (plain {@code Assert.*} behaviour). */
    STOP_ON_FAILURE,

    /** Soft assert: record the failure, keep going, fail the test once it finishes. */
    CONTINUE_ON_FAILURE,

    /** Informational check: record a warning, keep going, never fails the test. */
    OPTIONAL;

    /**
     * Parses a config/system-property value, ignoring case and surrounding blanks.
     *
     * @return the matching mode, or {@code fallback} when {@code raw} is null, blank or unknown
     */
    public static FailureHandling parse(String raw, FailureHandling fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
