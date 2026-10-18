package com.automation.core.recorder;

/** One row of the keyword script the recorder is about to write. */
public final class RecordedStep {

    public final String keyword;
    public final String locatorKey;
    public final String testData;
    public final String expected;
    public final String description;
    /** Browser timestamp of the event behind this step (used to merge double clicks). */
    final long ts;

    public RecordedStep(String keyword, String locatorKey, String testData, String expected, String description, long ts) {
        this.keyword = keyword;
        this.locatorKey = locatorKey == null ? "" : locatorKey;
        this.testData = testData == null ? "" : testData;
        this.expected = expected == null ? "" : expected;
        this.description = description == null ? "" : description;
        this.ts = ts;
    }

    @Override
    public String toString() {
        return keyword + (locatorKey.isEmpty() ? "" : " -> " + locatorKey);
    }
}
