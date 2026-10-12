package com.automation.core.quarantine;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a known-flaky test (or every test in a class) as quarantined: it still RUNS, so its
 * pass/fail history stays visible, but a failure no longer fails the build - it is reported as
 * skipped with a "Quarantined" message and the Allure label {@code quarantined}.
 *
 * <pre>{@code
 * @Test
 * @Quarantine(until = "2026-11-15", ticket = "QA-412", reason = "CAPTCHA solver misreads 'f'")
 * public void verifyLogin() { ... }
 * }</pre>
 *
 * <p>{@code until} is mandatory and is the LAST day the quarantine applies (ISO date,
 * {@code yyyy-MM-dd}). After it the test fails the build normally again, so a quarantine cannot
 * quietly become permanent: fix the test or consciously renew the date. A missing or unparsable
 * date makes the annotation ineffective (the test fails normally) and logs a warning.
 *
 * <p>The same thing can be done without touching the test class through
 * {@code quarantine.properties} at the repository root - see that file. Switch every quarantine
 * off for a run with {@code -Dquarantine.enabled=false} to see the unfiltered truth.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Quarantine {

    /** Last day (inclusive) the quarantine applies, ISO {@code yyyy-MM-dd}. */
    String until();

    /** Tracking ticket for the fix, e.g. {@code QA-412}. */
    String ticket() default "";

    /** Why the test is flaky, in a sentence. */
    String reason() default "";
}
