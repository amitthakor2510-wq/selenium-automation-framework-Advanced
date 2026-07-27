package com.automation.core.data;

import java.util.LinkedHashMap;
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
        // Store all keys lowercase for case-insensitive lookup
        Map<String, String> normalized = new LinkedHashMap<>();
        data.forEach((k, v) -> normalized.put(k.trim().toLowerCase(), v == null ? "" : v.trim()));
        this.data     = normalized;
        this.rowIndex = rowIndex;
    }

    /**
     * Get value by column header name (case-insensitive).
     * Returns empty string if column not found.
     */
    public String get(String columnName) {
        return data.getOrDefault(columnName.trim().toLowerCase(), "");
    }

    /**
     * Get value or throw if column is missing — use for required fields.
     */
    public String getRequired(String columnName) {
        String value = get(columnName);
        if (value.isEmpty()) {
            throw new RuntimeException(
                "Required column '" + columnName + "' is missing or empty in row " + rowIndex
                    + ". Available columns: " + data.keySet()
            );
        }
        return value;
    }

    public boolean has(String columnName) {
        return data.containsKey(columnName.trim().toLowerCase());
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
