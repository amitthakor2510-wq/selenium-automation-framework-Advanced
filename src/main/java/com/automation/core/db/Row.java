package com.automation.core.db;

import com.automation.core.utils.SensitiveData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One result row. Column names are lower-cased on the way in, so {@code row.get("status")} works
 * the same on PostgreSQL (which folds to lower case) and H2/Oracle (which fold to upper case).
 */
public final class Row {

    private final Map<String, Object> values = new LinkedHashMap<>();

    Row put(String column, Object value) {
        values.put(column.toLowerCase(java.util.Locale.ROOT), value);
        return this;
    }

    /** The column's value, or null when it is SQL NULL or the column does not exist (see {@link #has}). */
    public Object get(String column) {
        return values.get(column.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean has(String column) {
        return values.containsKey(column.toLowerCase(java.util.Locale.ROOT));
    }

    public String getString(String column) {
        Object value = get(column);
        return value == null ? null : value.toString();
    }

    public Long getLong(String column) {
        Object value = get(column);
        return value == null ? null : ((Number) value).longValue();
    }

    public Map<String, Object> asMap() {
        return java.util.Collections.unmodifiableMap(values);
    }

    /**
     * Equality used by the assertions: numbers compare by value ({@code 5} equals {@code 5L} equals
     * {@code new BigDecimal("5.00")}), everything else with {@link Objects#equals}.
     */
    public static boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number) {
            try {
                return new java.math.BigDecimal(expected.toString())
                    .compareTo(new java.math.BigDecimal(actual.toString())) == 0;
            } catch (NumberFormatException notFinite) {
                return Objects.equals(expected, actual); // NaN / Infinity have no BigDecimal form
            }
        }
        return Objects.equals(expected, actual);
    }

    /**
     * Column values for logs and failure messages, with the value of any column whose NAME looks
     * sensitive (password, token, secret, api key, ...) replaced by asterisks - a failed assertion
     * must not copy a credential from the database into a report. {@link #get} still returns the
     * real value to the test.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("{");
        for (Map.Entry<String, Object> column : values.entrySet()) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(column.getKey()).append('=')
                .append(SensitiveData.isSensitiveName(column.getKey()) ? SensitiveData.MASK : column.getValue());
        }
        return text.append('}').toString();
    }
}
