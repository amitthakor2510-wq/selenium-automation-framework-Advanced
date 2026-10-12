package com.automation.core.db;

import com.automation.core.utils.SensitiveData;
import org.testng.Assert;

import java.util.List;
import java.util.Map;

/**
 * Assertions over database content, in the same style as {@code ApiAssertions}: hard assertions
 * with a failure message that says what was queried and what was found. Wrap a call in
 * {@code Verify.check(...)} to make it a soft assertion.
 *
 * <p>Writes are eventually visible (queues, async jobs, replicas), so the {@code await*} methods
 * poll until the expectation holds or the timeout passes - use them instead of a fixed sleep.
 */
public final class DbAssertions {

    private static final long DEFAULT_POLL_MS = 250;
    private static final int MAX_PARAM_CHARS = 80;

    private DbAssertions() {
    }

    /** Exactly {@code expected} rows come back. */
    public static List<Row> assertRowCount(DbClient db, int expected, String sql, Object... params) {
        List<Row> rows = db.query(sql, params);
        if (rows.size() != expected) {
            Assert.fail("Expected " + expected + " row(s) but got " + rows.size() + " for: " + describe(sql, params)
                + (rows.isEmpty() ? "" : "\nRows: " + rows));
        }
        return rows;
    }

    /** At least one row comes back; returns the first. */
    public static Row assertRowExists(DbClient db, String sql, Object... params) {
        List<Row> rows = db.query(sql, params);
        if (rows.isEmpty()) {
            Assert.fail("Expected a row but none found for: " + describe(sql, params));
        }
        return rows.get(0);
    }

    /** Nothing comes back, e.g. after a delete. */
    public static void assertNoRows(DbClient db, String sql, Object... params) {
        List<Row> rows = db.query(sql, params);
        if (!rows.isEmpty()) {
            Assert.fail("Expected no rows but got " + rows.size() + " for: " + describe(sql, params)
                + "\nRows: " + rows);
        }
    }

    /** First column of the first row equals {@code expected} (numbers compare by value). */
    public static void assertValue(DbClient db, Object expected, String sql, Object... params) {
        Object actual = db.scalar(sql, params);
        if (!Row.valuesEqual(expected, actual)) {
            // the query text is the only hint what the value is: a SELECT of an api_key / password / token must not echo it
            boolean sensitive = SensitiveData.isSensitiveName(sql);
            Assert.fail("Expected value <" + (sensitive ? SensitiveData.MASK : expected) + "> but was <"
                + (sensitive ? SensitiveData.MASK : actual) + "> for: " + describe(sql, params));
        }
    }

    /** The first row has every listed column with the listed value; other columns are ignored. */
    public static Row assertRow(DbClient db, Map<String, Object> expectedColumns, String sql, Object... params) {
        Row row = assertRowExists(db, sql, params);
        StringBuilder problems = new StringBuilder();
        for (Map.Entry<String, Object> expected : expectedColumns.entrySet()) {
            if (!row.has(expected.getKey())) {
                problems.append("\n  column '").append(expected.getKey()).append("' is not in the result");
            } else if (!Row.valuesEqual(expected.getValue(), row.get(expected.getKey()))) {
                problems.append("\n  column '").append(expected.getKey()).append("': expected <")
                    .append(expected.getValue()).append("> but was <").append(row.get(expected.getKey())).append('>');
            }
        }
        if (problems.length() > 0) {
            Assert.fail("Row does not match for: " + describe(sql, params) + problems + "\nRow: " + row);
        }
        return row;
    }

    /** Polls until the query returns at least one row, then returns the first; fails after {@code timeoutMs}. */
    public static Row awaitRow(DbClient db, long timeoutMs, String sql, Object... params) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            List<Row> rows = db.query(sql, params);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            if (System.nanoTime() >= deadline) {
                Assert.fail("No row appeared within " + timeoutMs + " ms for: " + describe(sql, params));
            }
            pause();
        }
    }

    /** Polls until the query returns exactly {@code expected} rows; fails after {@code timeoutMs}. */
    public static void awaitRowCount(DbClient db, int expected, long timeoutMs, String sql, Object... params) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            int actual = db.query(sql, params).size();
            if (actual == expected) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                Assert.fail("Expected " + expected + " row(s) within " + timeoutMs + " ms but last saw " + actual
                    + " for: " + describe(sql, params));
            }
            pause();
        }
    }

    /** SQL plus its parameters, each cut to {@value #MAX_PARAM_CHARS} characters so a huge value cannot flood a report. */
    static String describe(String sql, Object... params) {
        if (params.length == 0) {
            return DbClient.oneLine(sql);
        }
        StringBuilder text = new StringBuilder(DbClient.oneLine(sql)).append("  params=[");
        for (int i = 0; i < params.length; i++) {
            String value = String.valueOf(params[i]);
            text.append(i == 0 ? "" : ", ")
                .append(value.length() > MAX_PARAM_CHARS ? value.substring(0, MAX_PARAM_CHARS) + "..." : value);
        }
        return text.append(']').toString();
    }

    private static void pause() {
        try {
            Thread.sleep(DEFAULT_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assert.fail("Interrupted while waiting for the database");
        }
    }
}
