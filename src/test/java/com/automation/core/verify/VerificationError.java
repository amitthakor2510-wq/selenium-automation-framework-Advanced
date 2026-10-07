package com.automation.core.verify;

import java.util.List;

/**
 * Thrown (by {@link Verify#applyToResult}) at the end of a test that recorded one or more
 * {@link FailureHandling#CONTINUE_ON_FAILURE} failures. It is an {@link AssertionError}, so
 * reports and the retry logic treat it exactly like a normal failed assertion. Each individual
 * failure is attached as a suppressed exception so its own stack trace is still available.
 */
public final class VerificationError extends AssertionError {

    private static final long serialVersionUID = 1L;

    private final transient List<Verify.Failure> failures;

    public VerificationError(List<Verify.Failure> failures) {
        super(buildMessage(failures));
        this.failures = List.copyOf(failures);
        for (Verify.Failure failure : failures) {
            addSuppressed(failure.error());
        }
    }

    /** Every failure that was collected, in the order it happened. */
    public List<Verify.Failure> getFailures() {
        return failures;
    }

    private static String buildMessage(List<Verify.Failure> failures) {
        StringBuilder message = new StringBuilder();
        message.append(failures.size()).append(" verification(s) failed:");
        int index = 1;
        for (Verify.Failure failure : failures) {
            message.append(System.lineSeparator()).append("  ").append(index++).append(") ")
                .append(failure.message());
        }
        return message.toString();
    }
}
