package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-thread variable store for keyword scripts. {@code STORE_TEXT} writes a value, and any
 * later {@code testData} / {@code expected} cell can read it back as {@code ${var:name}}
 * (see {@link PlaceholderResolver}). A fresh {@link KeywordEngine} clears the store, so
 * variables never leak from one test into the next on a reused worker thread.
 */
public final class KeywordVariables {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final ThreadLocal<Map<String, String>> STORE = ThreadLocal.withInitial(HashMap::new);

    private KeywordVariables() {
    }

    public static void put(String name, String value) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new KeywordExecutionException("[KeywordVariables] Invalid variable name '" + name
                + "' - use letters, digits, '_', '.' or '-'");
        }
        STORE.get().put(name, value == null ? "" : value);
    }

    /** The stored value, or {@code null} if the variable has not been set on this thread. */
    public static String get(String name) {
        return STORE.get().get(name);
    }

    public static void clear() {
        STORE.get().clear();
    }
}
