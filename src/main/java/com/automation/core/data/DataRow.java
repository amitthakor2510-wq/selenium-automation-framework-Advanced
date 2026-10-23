package com.automation.core.data;

import com.automation.core.exceptions.DataFileException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Represents one row of test data.
 * Access values by column header name — case-insensitive.
 *
 * Example Excel row:
 *   username | password | expected
 *   john     | pass123  | success
 *
 * Usage:
 *   row.get("username")  → "john"
 *   row.get("PASSWORD")  → "pass123"
 */
public class DataRow {

    private final Map<String, String> data;
    private final int rowIndex;

    public DataRow(Map<String, String> data, int rowIndex) {
        this(data, rowIndex, true);
    }

    private DataRow(Map<String, String> data, int rowIndex, boolean trimValues) {
        // Store all keys lowercase for case-insensitive lookup. Locale.ROOT
        // (not the JVM default) so a Turkish-locale machine doesn't turn
        // "ID" into a dotless-i "ıd" that no lookup ever matches.
        Map<String, String> normalized = new LinkedHashMap<>();
        data.forEach((k, v) -> normalized.put(normalizeKey(k),
            v == null ? "" : (trimValues ? v.trim() : v)));
        this.data     = normalized;
        this.rowIndex = rowIndex;
    }

    /**
     * Like {@link #DataRow(Map, int)} but keeps cell VALUES exactly as given
     * (column names are still trimmed + lowercased). The normal constructor
     * trims values on purpose — spreadsheets routinely carry stray spaces —
     * but that makes it impossible to build a row whose whole point is
     * leading/trailing/only whitespace, e.g. the synthetic edge-case rows
     * (a whitespace-only username would otherwise collapse into the
     * empty-string case, and "  padded  " into "padded"). File readers must
     * keep using the trimming constructor.
     */
    public static DataRow preservingWhitespace(Map<String, String> data, int rowIndex) {
        return new DataRow(data, rowIndex, false);
    }

    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Get value by column header name (case-insensitive).
     * Returns empty string if column not found.
     */
    public String get(String columnName) {
        return data.getOrDefault(normalizeKey(columnName), "");
    }

    /**
     * Get value or throw if column is missing — use for required fields.
     */
    public String getRequired(String columnName) {
        String value = get(columnName);
        if (value.isEmpty()) {
            throw new DataFileException(
                "Required column '" + columnName + "' is missing or empty in row " + rowIndex
                    + ". Available columns: " + data.keySet()
            );
        }
        return value;
    }

    public boolean has(String columnName) {
        return data.containsKey(normalizeKey(columnName));
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public Map<String, String> toMap() {
        return new LinkedHashMap<>(data);
    }

    @Override
    public String toString() {
        return "DataRow[" + rowIndex + "] " + data;
    }
}
