package com.automation.core.report.pdf;

import com.automation.core.mail.EmailReportBuilder;

/**
 * Turns a {@link Throwable} into the redacted text the PDF shows: a one-line message plus a condensed
 * stack trace with test-framework and reflection noise removed, so the lines that matter (the
 * framework's and the tests' own frames) are the ones that fit on the page.
 */
public final class ThrowableText {

    private static final String[] NOISE = {
        "org.testng.", "java.base/jdk.internal.", "jdk.internal.", "java.lang.reflect.", "sun.reflect.",
        "org.apache.maven.surefire.", "org.junit.platform.", "java.base/java.util.concurrent.",
        "java.base/java.lang.Thread."
    };

    private static final int MAX_CAUSES = 3;

    private ThrowableText() {
    }

    /** {@code SomeException: message}, whitespace-collapsed, secrets masked and shortened. */
    public static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return EmailReportBuilder.sanitizeMessage(t.getClass().getName()
            + (t.getMessage() == null ? "" : ": " + t.getMessage()));
    }

    /**
     * Condensed stack trace, one frame per line, at most {@code maxLines} lines in total (0 = none).
     * Includes up to three "Caused by" lines because the root cause is often the useful part.
     */
    public static String stackTrace(Throwable t, int maxLines) {
        if (t == null || maxLines <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        Throwable current = t;
        for (int depth = 0; current != null && depth <= MAX_CAUSES && lines < maxLines; depth++) {
            if (depth > 0) {
                sb.append("Caused by: ").append(message(current)).append('\n');
                lines++;
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (lines >= maxLines) {
                    break;
                }
                String text = frame.toString();
                if (isNoise(text)) {
                    continue;
                }
                sb.append("at ").append(EmailReportBuilder.sanitizeMessage(text)).append('\n');
                lines++;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return sb.toString().stripTrailing();
    }

    private static boolean isNoise(String frame) {
        for (String prefix : NOISE) {
            if (frame.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
