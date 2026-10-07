package com.automation.core.api.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free JSON path reader used by the no-code API runner.
 *
 * <pre>
 *   id                      field of the root object
 *   data.user.name          nested fields
 *   items[0].title          array index   (negative counts from the end: items[-1])
 *   items[*].id             every element -> a list of ids
 *   items[?id=3].title      elements whose "id" equals 3 (string comparison), then their title
 *   items.size()            number of elements (also: length(), count())
 *   $ / $.id / $[0].id      "$" is the root (optional prefix)
 *   ['odd.key']             bracket + quotes for keys that contain dots or spaces
 * </pre>
 *
 * A path that does not exist returns {@link #MISSING} (not null) so callers can tell
 * "field is absent" apart from "field is present and null".
 */
public final class JsonPathLite {

    /** Marker for "this path does not exist in the document". */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<missing>";
        }
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPathLite() {
    }

    /** Parses JSON text into plain Java objects (Map / List / String / Number / Boolean / null). */
    public static Object parse(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, Object.class);
    }

    /** Reads {@code path} from an already-parsed document. */
    public static Object read(Object document, String path) {
        List<Object> tokens = tokenize(path);
        return eval(document, tokens, 0);
    }

    // ---- tokenizer ---------------------------------------------------------------------

    private record Index(int value) {
    }

    private record Wildcard() {
    }

    private record Filter(String key, String value) {
    }

    private record Name(String value) {
    }

    private static List<Object> tokenize(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.equals("$")) {
            path = "";
        } else if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$[")) {
            path = path.substring(1);
        }
        List<Object> tokens = new ArrayList<>();
        int i = 0;
        int n = path.length();
        StringBuilder name = new StringBuilder();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(name, tokens);
                i++;
            } else if (c == '[') {
                flush(name, tokens);
                int end = findClosingBracket(path, i);
                String inner = path.substring(i + 1, end).trim();
                tokens.add(bracketToken(inner));
                i = end + 1;
            } else {
                name.append(c);
                i++;
            }
        }
        flush(name, tokens);
        return tokens;
    }

    private static void flush(StringBuilder name, List<Object> tokens) {
        if (name.length() > 0) {
            tokens.add(new Name(name.toString()));
            name.setLength(0);
        }
    }

    private static int findClosingBracket(String path, int open) {
        char quote = 0;
        for (int j = open + 1; j < path.length(); j++) {
            char c = path.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ']') {
                return j;
            }
        }
        throw new IllegalArgumentException("Unclosed '[' in path: " + path);
    }

    private static Object bracketToken(String inner) {
        if (inner.equals("*")) {
            return new Wildcard();
        }
        if (inner.startsWith("?")) {
            String expr = inner.substring(1).trim();
            if (expr.startsWith("(") && expr.endsWith(")")) {
                expr = expr.substring(1, expr.length() - 1).trim();
            }
            if (expr.startsWith("@.")) {
                expr = expr.substring(2);
            }
            int eq = expr.indexOf("==") >= 0 ? expr.indexOf("==") : expr.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Filter must look like [?key=value]: [" + inner + "]");
            }
            String key = expr.substring(0, eq).trim();
            String value = expr.substring(expr.startsWith("==", eq) ? eq + 2 : eq + 1).trim();
            return new Filter(key, unquote(value));
        }
        if ((inner.startsWith("'") && inner.endsWith("'")) || (inner.startsWith("\"") && inner.endsWith("\""))) {
            return new Name(unquote(inner));
        }
        try {
            return new Index(Integer.parseInt(inner));
        } catch (NumberFormatException e) {
            return new Name(inner);
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- evaluator ---------------------------------------------------------------------

    private static Object eval(Object current, List<Object> tokens, int idx) {
        if (current == MISSING) {
            return MISSING;
        }
        if (idx >= tokens.size()) {
            return current;
        }
        Object token = tokens.get(idx);
        if (token instanceof Name nameToken) {
            String name = nameToken.value();
            if (current instanceof Map<?, ?> map) {
                if (map.containsKey(name)) {
                    return eval(map.get(name), tokens, idx + 1);
                }
                if (isSizeFunction(name)) {
                    return eval(map.size(), tokens, idx + 1);
                }
                return MISSING;
            }
            if (isSizeFunction(name)) {
                if (current instanceof List<?> list) {
                    return eval(list.size(), tokens, idx + 1);
                }
                if (current instanceof String s) {
                    return eval(s.length(), tokens, idx + 1);
                }
                return MISSING;
            }
            if (current instanceof List<?> list) {
                // "items.id" on a list maps over the elements, same as items[*].id
                return mapOverList(list, tokens, idx);
            }
            return MISSING;
        }
        if (token instanceof Index indexToken) {
            if (!(current instanceof List<?> list)) {
                return MISSING;
            }
            int i = indexToken.value() < 0 ? list.size() + indexToken.value() : indexToken.value();
            if (i < 0 || i >= list.size()) {
                return MISSING;
            }
            return eval(list.get(i), tokens, idx + 1);
        }
        if (token instanceof Wildcard) {
            if (!(current instanceof List<?> list)) {
                return MISSING;
            }
            return mapOverList(list, tokens, idx + 1);
        }
        if (token instanceof Filter filter) {
            if (!(current instanceof List<?> list)) {
                return MISSING;
            }
            List<Object> matched = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map && map.containsKey(filter.key())
                    && String.valueOf(map.get(filter.key())).equals(filter.value())) {
                    matched.add(element);
                }
            }
            if (idx + 1 >= tokens.size()) {
                return matched;
            }
            return mapOverList(matched, tokens, idx + 1);
        }
        return MISSING;
    }

    private static Object mapOverList(List<?> list, List<Object> tokens, int startIdx) {
        // Size-style functions apply to the list itself, not to each element.
        if (startIdx < tokens.size() && tokens.get(startIdx) instanceof Name n && isSizeFunction(n.value())) {
            return eval(list.size(), tokens, startIdx + 1);
        }
        List<Object> out = new ArrayList<>();
        for (Object element : list) {
            Object value = eval(element, tokens, startIdx);
            if (value != MISSING) {
                out.add(value);
            }
        }
        return out;
    }

    private static boolean isSizeFunction(String name) {
        return name.equals("size()") || name.equals("length()") || name.equals("count()");
    }
}
