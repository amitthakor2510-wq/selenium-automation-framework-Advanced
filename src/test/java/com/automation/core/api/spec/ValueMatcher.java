package com.automation.core.api.spec;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compares one actual value against what a spec expects. Returns {@code null} when it matches, or a
 * short human-readable reason when it does not.
 *
 * <p>What a spec author can write on the right-hand side:
 * <pre>
 *   title: "hello"                 equals (numbers compare numerically: 5 equals 5.0)
 *   id: 7                          equals
 *   done: false                    equals
 *   token: notNull                 keywords: notNull, null, empty, notEmpty, absent, exists
 *   tags: [a, b]                   whole-list equality
 *   price: { gt: 0, lte: 1000 }    operators (all must hold)
 * </pre>
 * Operators: eq, ne, gt, gte, lt, lte, between:[a,b], contains, notContains, startsWith, endsWith,
 * equalsIgnoreCase, matches (regex, found anywhere), type (string|number|integer|boolean|array|object|null),
 * size, sizeGt, sizeGte, sizeLt, in:[..], notIn:[..], notNull, null, empty, notEmpty, exists, absent.
 * To compare against the literal text "notNull" use {@code { eq: notNull }}.
 */
public final class ValueMatcher {

    private static final Set<String> KEYWORDS = Set.of("notNull", "null", "empty", "notEmpty", "absent", "exists");
    private static final Set<String> OPERATORS = Set.of(
        "eq", "ne", "gt", "gte", "lt", "lte", "between", "contains", "notContains", "startsWith", "endsWith",
        "equalsIgnoreCase", "matches", "type", "size", "sizeGt", "sizeGte", "sizeLt", "in", "notIn",
        "notNull", "null", "empty", "notEmpty", "exists", "absent");

    private ValueMatcher() {
    }

    /** @return null if {@code actual} satisfies {@code expected}, otherwise why not. */
    public static String check(Object expected, Object actual) {
        boolean missing = actual == JsonPathLite.MISSING;

        if (expected instanceof String s && KEYWORDS.contains(s)) {
            return keyword(s, actual, missing);
        }
        if (expected instanceof Map<?, ?> map && isOperatorMap(map)) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String failure = operator(String.valueOf(e.getKey()), e.getValue(), actual, missing);
                if (failure != null) {
                    return failure;
                }
            }
            return null;
        }
        if (missing) {
            return "path not found in response (expected " + show(expected) + ")";
        }
        return deepEquals(expected, actual) ? null : "expected " + show(expected) + " but was " + show(actual);
    }

    private static boolean isOperatorMap(Map<?, ?> map) {
        if (map.isEmpty()) {
            return false;
        }
        for (Object key : map.keySet()) {
            if (!OPERATORS.contains(String.valueOf(key))) {
                return false;
            }
        }
        return true;
    }

    private static String keyword(String keyword, Object actual, boolean missing) {
        switch (keyword) {
            case "absent":
                return missing ? null : "expected path to be absent but was " + show(actual);
            case "exists":
                return missing ? "path not found in response" : null;
            case "notNull":
                return missing || actual == null ? "expected a value but it was " + (missing ? "missing" : "null") : null;
            case "null":
                return !missing && actual == null ? null : "expected null but was " + (missing ? "missing" : show(actual));
            case "empty":
                return !missing && isEmpty(actual) ? null : "expected empty but was " + (missing ? "missing" : show(actual));
            case "notEmpty":
                return !missing && actual != null && !isEmpty(actual) ? null : "expected non-empty but was " + (missing ? "missing" : show(actual));
            default:
                return "unknown keyword " + keyword;
        }
    }

    private static String operator(String op, Object arg, Object actual, boolean missing) {
        switch (op) {
            case "absent":
            case "exists":
            case "notNull":
            case "null":
            case "empty":
            case "notEmpty":
                if (arg instanceof Boolean b && !b) {
                    // "{ notNull: false }" is a no-op rather than an inverted check, to avoid double negatives
                    return null;
                }
                return keyword(op, actual, missing);
            default:
                break;
        }
        if (missing) {
            return "path not found in response (operator '" + op + "')";
        }
        switch (op) {
            case "eq":
                return deepEquals(arg, actual) ? null : "expected " + show(arg) + " but was " + show(actual);
            case "ne":
                return deepEquals(arg, actual) ? "expected value to differ from " + show(arg) : null;
            case "gt":
                return compare(actual, arg, c -> c > 0, "greater than");
            case "gte":
                return compare(actual, arg, c -> c >= 0, "greater than or equal to");
            case "lt":
                return compare(actual, arg, c -> c < 0, "less than");
            case "lte":
                return compare(actual, arg, c -> c <= 0, "less than or equal to");
            case "between":
                return between(actual, arg);
            case "contains":
                return contains(actual, arg) ? null : "expected " + show(actual) + " to contain " + show(arg);
            case "notContains":
                return contains(actual, arg) ? "expected " + show(actual) + " NOT to contain " + show(arg) : null;
            case "startsWith":
                return String.valueOf(actual).startsWith(String.valueOf(arg)) ? null
                    : "expected " + show(actual) + " to start with " + show(arg);
            case "endsWith":
                return String.valueOf(actual).endsWith(String.valueOf(arg)) ? null
                    : "expected " + show(actual) + " to end with " + show(arg);
            case "equalsIgnoreCase":
                return String.valueOf(actual).equalsIgnoreCase(String.valueOf(arg)) ? null
                    : "expected " + show(actual) + " to equal (ignoring case) " + show(arg);
            case "matches":
                try {
                    return Pattern.compile(String.valueOf(arg)).matcher(String.valueOf(actual)).find() ? null
                        : "expected " + show(actual) + " to match regex " + show(arg);
                } catch (PatternSyntaxException e) {
                    return "invalid regex " + show(arg) + ": " + e.getDescription();
                }
            case "type":
                return typeOf(actual).equals(String.valueOf(arg).toLowerCase(Locale.ROOT))
                    || ("number".equalsIgnoreCase(String.valueOf(arg)) && typeOf(actual).equals("integer"))
                    ? null : "expected type " + arg + " but was " + typeOf(actual);
            case "size":
                return sizeCheck(actual, arg, c -> c == 0, "of");
            case "sizeGt":
                return sizeCheck(actual, arg, c -> c > 0, "greater than");
            case "sizeGte":
                return sizeCheck(actual, arg, c -> c >= 0, "at least");
            case "sizeLt":
                return sizeCheck(actual, arg, c -> c < 0, "less than");
            case "in":
                return arg instanceof Collection<?> c && c.stream().anyMatch(item -> deepEquals(item, actual)) ? null
                    : "expected " + show(actual) + " to be one of " + show(arg);
            case "notIn":
                return arg instanceof Collection<?> c && c.stream().anyMatch(item -> deepEquals(item, actual))
                    ? "expected " + show(actual) + " NOT to be one of " + show(arg) : null;
            default:
                return "unknown operator '" + op + "'";
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String compare(Object actual, Object bound, java.util.function.IntPredicate ok, String words) {
        Double a = toNumber(actual);
        Double b = toNumber(bound);
        if (a == null || b == null) {
            return "cannot compare " + show(actual) + " with " + show(bound) + " numerically";
        }
        return ok.test(Double.compare(a, b)) ? null : "expected " + show(actual) + " to be " + words + " " + show(bound);
    }

    private static String between(Object actual, Object arg) {
        if (!(arg instanceof List<?> bounds) || bounds.size() != 2) {
            return "'between' needs a two-item list, e.g. between: [1, 10]";
        }
        String low = compare(actual, bounds.get(0), c -> c >= 0, "greater than or equal to");
        return low != null ? low : compare(actual, bounds.get(1), c -> c <= 0, "less than or equal to");
    }

    private static String sizeCheck(Object actual, Object arg, java.util.function.IntPredicate ok, String words) {
        int size;
        if (actual instanceof Collection<?> c) {
            size = c.size();
        } else if (actual instanceof Map<?, ?> m) {
            size = m.size();
        } else if (actual instanceof String s) {
            size = s.length();
        } else {
            return "size check needs a list, object or string but was " + typeOf(actual);
        }
        Double want = toNumber(arg);
        if (want == null) {
            return "size needs a number, got " + show(arg);
        }
        return ok.test(Double.compare(size, want)) ? null : "expected size " + words + " " + show(arg) + " but was " + size;
    }

    private static boolean contains(Object actual, Object needle) {
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(item -> deepEquals(needle, item));
        }
        if (actual instanceof Map<?, ?> m) {
            return m.containsKey(String.valueOf(needle));
        }
        return String.valueOf(actual).contains(String.valueOf(needle));
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return value instanceof Map<?, ?> m && m.isEmpty();
    }

    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Collection) {
            return "array";
        }
        return value instanceof Map ? "object" : value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static Double toNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Equality that treats 5 and 5.0 as equal, "5" and 5 as equal, and recurses into lists/maps. */
    static boolean deepEquals(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Number || actual instanceof Number) {
            Double a = toNumber(actual);
            Double e = toNumber(expected);
            return a != null && e != null && Double.compare(a, e) == 0;
        }
        if (expected instanceof Boolean || actual instanceof Boolean) {
            return String.valueOf(expected).equalsIgnoreCase(String.valueOf(actual));
        }
        if (expected instanceof List<?> el && actual instanceof List<?> al) {
            if (el.size() != al.size()) {
                return false;
            }
            for (int i = 0; i < el.size(); i++) {
                if (!deepEquals(el.get(i), al.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Map<?, ?> em && actual instanceof Map<?, ?> am) {
            if (em.size() != am.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : em.entrySet()) {
                if (!am.containsKey(String.valueOf(e.getKey()))
                    || !deepEquals(e.getValue(), am.get(String.valueOf(e.getKey())))) {
                    return false;
                }
            }
            return true;
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    /** Short printable form for failure messages. */
    static String show(Object value) {
        if (value == JsonPathLite.MISSING) {
            return "<missing>";
        }
        String text = value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }
}
