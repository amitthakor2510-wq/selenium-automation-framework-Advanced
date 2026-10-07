package com.automation.core.api.spec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${...}} placeholders in spec values.
 *
 * <pre>
 *   ${name}                a variable (from "vars:", a data row, or an earlier request's "extract:")
 *   ${row.email}           nested value inside a map variable (data-driven rows are also exposed as ${email})
 *   ${name:-fallback}      fallback when the variable is not defined
 *   ${env:API_TOKEN}       operating-system environment variable
 *   ${prop:api.auth.token} framework config key / -D system property
 *   ${file:payloads/a.json} text content of a file (relative to the spec file's folder)
 *   ${uuid} ${timestamp} ${date} ${datetime} ${randomInt} ${randomInt:1:100} ${randomString:12} ${randomEmail}
 * </pre>
 *
 * If the whole string is exactly one placeholder and the value is not text (number, boolean, list,
 * object), the original type is kept - so {@code "id": "${postId}"} sends a JSON number if postId is one.
 */
public final class VariableResolver {

    /** Thrown when a placeholder names a variable that is not (yet) defined. */
    public static final class UnresolvedVariableException extends RuntimeException {
        private final String variable;

        public UnresolvedVariableException(String variable) {
            super("Variable ${" + variable + "} is not defined");
            this.variable = variable;
        }

        public String variable() {
            return variable;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Function<String, String> propLookup;
    private final Path baseDir;

    /**
     * @param propLookup resolves {@code ${prop:key}} (return null when the key does not exist)
     * @param baseDir    folder that relative {@code ${file:...}} paths are resolved against (may be null)
     */
    public VariableResolver(Function<String, String> propLookup, Path baseDir) {
        this.propLookup = propLookup == null ? k -> null : propLookup;
        this.baseDir = baseDir;
    }

    /** Resolves a template string against {@code vars}; the result is always text. */
    public String resolveText(String template, Map<String, Object> vars) {
        if (template == null || template.indexOf("${") < 0) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = lookup(m.group(1).trim(), vars);
            m.appendReplacement(sb, Matcher.quoteReplacement(asText(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Resolves any spec value (string / map / list), keeping non-text types for single-placeholder strings. */
    public Object resolve(Object value, Map<String, Object> vars) {
        if (value instanceof String s) {
            Matcher m = PLACEHOLDER.matcher(s);
            if (m.matches()) {
                return lookup(m.group(1).trim(), vars);
            }
            return resolveText(s, vars);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(resolveText(String.valueOf(e.getKey()), vars), resolve(e.getValue(), vars));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(resolve(item, vars));
            }
            return out;
        }
        return value;
    }

    /** Names of every placeholder inside a value, e.g. {@code ["postId", "env:TOKEN"]}. */
    public static List<String> placeholdersIn(Object value) {
        List<String> found = new ArrayList<>();
        collect(value, found);
        return found;
    }

    private static void collect(Object value, List<String> found) {
        if (value instanceof String s) {
            Matcher m = PLACEHOLDER.matcher(s);
            while (m.find()) {
                found.add(m.group(1).trim());
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(String.valueOf(e.getKey()), found);
                collect(e.getValue(), found);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collect(item, found);
            }
        }
    }

    private Object lookup(String expression, Map<String, Object> vars) {
        String name = expression;
        String fallback = null;
        int fb = expression.indexOf(":-");
        if (fb >= 0) {
            name = expression.substring(0, fb).trim();
            fallback = expression.substring(fb + 2);
        }
        Object value = lookupName(name, vars);
        if (value == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new UnresolvedVariableException(name);
        }
        return value;
    }

    private Object lookupName(String name, Map<String, Object> vars) {
        if (name.startsWith("env:")) {
            return System.getenv(name.substring(4));
        }
        if (name.startsWith("prop:")) {
            return propLookup.apply(name.substring(5));
        }
        if (name.startsWith("file:")) {
            return readFile(name.substring(5));
        }
        // User variables win over built-ins, so a spec can define its own "date" or "uuid".
        Object fromVars = fromVars(name, vars);
        if (fromVars != null) {
            return fromVars;
        }
        return builtin(name);
    }

    private static Object fromVars(String name, Map<String, Object> vars) {
        if (vars == null) {
            return null;
        }
        if (vars.containsKey(name)) {
            return vars.get(name);
        }
        if (name.indexOf('.') > 0 || name.indexOf('[') > 0) {
            Object v = JsonPathLite.read(vars, name);
            return v == JsonPathLite.MISSING ? null : v;
        }
        return null;
    }

    private Object readFile(String relative) {
        try {
            Path p = Path.of(relative);
            if (!p.isAbsolute() && baseDir != null && Files.exists(baseDir.resolve(relative))) {
                p = baseDir.resolve(relative);
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read ${file:" + relative + "}: " + e.getMessage(), e);
        }
    }

    private static Object builtin(String name) {
        String[] parts = name.split(":");
        switch (parts[0]) {
            case "uuid":
                return UUID.randomUUID().toString();
            case "timestamp":
                return String.valueOf(System.currentTimeMillis());
            case "date":
                return LocalDate.now().toString();
            case "datetime":
                return Instant.now().toString();
            case "randomInt":
                return randomInt(parts);
            case "randomString":
                return randomString(parts);
            case "randomEmail":
                return "qa_" + Long.toString(System.nanoTime(), 36) + RANDOM.nextInt(1000) + "@example.com";
            default:
                return null;
        }
    }

    private static int randomInt(String[] parts) {
        int min = parts.length > 2 ? Integer.parseInt(parts[1]) : 0;
        int max = parts.length > 2 ? Integer.parseInt(parts[2]) : 1_000_000;
        return min + RANDOM.nextInt(Math.max(1, max - min + 1));
    }

    private static String randomString(String[] parts) {
        int len = parts.length > 1 ? Integer.parseInt(parts[1]) : 8;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    private static String asText(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }
}
