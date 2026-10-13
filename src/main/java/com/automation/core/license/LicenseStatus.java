package com.automation.core.license;

/**
 * Outcome of checking one dependency's license(s) against the {@link LicensePolicy}.
 * Declared worst-first so a plain enum-ordinal sort puts the rows that need attention on top.
 */
public enum LicenseStatus {
    /** Every declared license is on the deny-list. */
    DENIED,
    /** A license was found but is on neither list (or its text was not recognised) - a human decides. */
    REVIEW,
    /** No license information could be found for the artifact at all. */
    MISSING,
    /** At least one declared license is on the allow-list. */
    ALLOWED
}
