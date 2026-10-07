package com.automation.core.api.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One request + its expectations, read from a spec file. Thin typed view over the raw YAML map so
 * new keys can be added without touching a pile of fields.
 */
public final class ApiCase {

    private final SpecFile spec;
    private final Map<String, Object> raw;
    private final String name;
    private final Map<String, Object> row;

    ApiCase(SpecFile spec, Map<String, Object> raw, String name, Map<String, Object> row) {
        this.spec = spec;
        this.raw = raw;
        this.name = name;
        this.row = row;
    }

    public SpecFile spec() {
        return spec;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public String name() {
        return name;
    }

    /** Data-driven row for this case (empty when the case is not data-driven). */
    public Map<String, Object> row() {
        return row;
    }

    /** "file.yml :: case name" - unique, readable id for reports. */
    public String displayName() {
        return spec.name() + " :: " + name;
    }

    public String method() {
        return String.valueOf(raw.getOrDefault("method", "GET")).toUpperCase(java.util.Locale.ROOT);
    }

    public String path() {
        Object p = raw.containsKey("path") ? raw.get("path") : raw.get("url");
        return p == null ? "" : String.valueOf(p);
    }

    public Object get(String key) {
        return raw.get(key);
    }

    public boolean has(String key) {
        return raw.containsKey(key) && raw.get(key) != null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = raw.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    public Set<String> tags() {
        Set<String> tags = new LinkedHashSet<>();
        Object t = raw.get("tags");
        if (t instanceof List<?> list) {
            list.forEach(x -> tags.add(String.valueOf(x).trim().toLowerCase(java.util.Locale.ROOT)));
        } else if (t != null) {
            for (String part : String.valueOf(t).split(",")) {
                if (!part.isBlank()) {
                    tags.add(part.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        tags.addAll(spec.defaultTags());
        return Collections.unmodifiableSet(tags);
    }

    /** Non-null reason when the case is switched off with {@code skip: true} / {@code skip: "reason"}. */
    public String skipReason() {
        Object skip = raw.get("skip");
        if (skip == null || Boolean.FALSE.equals(skip)) {
            return null;
        }
        return Boolean.TRUE.equals(skip) ? "skip: true in spec" : String.valueOf(skip);
    }

    /** Variables visible to this case: spec variables, then data row (also under "row"). */
    public Map<String, Object> scope() {
        Map<String, Object> scope = new LinkedHashMap<>(spec.vars());
        scope.putAll(row);
        scope.put("row", row);
        return scope;
    }

    /** Names this case promises to define for later cases. */
    public List<String> extractNames() {
        return new ArrayList<>(map("extract").keySet());
    }
}
