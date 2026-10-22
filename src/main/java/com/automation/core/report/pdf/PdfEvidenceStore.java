package com.automation.core.report.pdf;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds failure screenshots in memory until the end-of-run PDF is built. The test listener already
 * captures a screenshot when a test fails; this keeps a copy (only when the PDF report is enabled)
 * so the PDF can show it without re-reading files or taking a second capture.
 *
 * <p>Bounded on purpose: at most {@link #MAX_ENTRIES} screenshots and {@link #MAX_BYTES} in total are
 * kept; once full, further screenshots are dropped (the report just shows the failure without one).
 * A retried test replaces its earlier screenshot, so the PDF shows the last attempt.
 */
public final class PdfEvidenceStore {

    static final int MAX_ENTRIES = 100;
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final Map<String, byte[]> SHOTS = new LinkedHashMap<>();
    private static long totalBytes;

    private PdfEvidenceStore() {
    }

    /**
     * Identity of one test invocation. Parameters are folded into a hash and never stored, so a
     * data-driven row's values (which can include credentials) cannot leak into memory dumps or logs.
     */
    public static String key(String className, String methodName, Object[] parameters) {
        int paramHash = parameters == null ? 0 : Arrays.deepToString(parameters).hashCode();
        return className + '#' + methodName + '#' + paramHash;
    }

    /** Stores (or replaces) the screenshot for a test; ignored when empty or when the store is full. */
    public static synchronized void put(String key, byte[] image) {
        if (key == null || image == null || image.length == 0) {
            return;
        }
        byte[] previous = SHOTS.get(key);
        long projected = totalBytes - (previous == null ? 0 : previous.length) + image.length;
        if (previous == null && SHOTS.size() >= MAX_ENTRIES || projected > MAX_BYTES) {
            return;
        }
        SHOTS.put(key, image);
        totalBytes = projected;
    }

    /** The screenshot stored for this test, or null. */
    public static synchronized byte[] get(String key) {
        return SHOTS.get(key);
    }

    public static synchronized int size() {
        return SHOTS.size();
    }

    /** Drops everything; called once the PDF has been written so a long-lived JVM does not keep the images. */
    public static synchronized void clear() {
        SHOTS.clear();
        totalBytes = 0;
    }
}
