package com.automation.core.api.spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One spec file (YAML / JSON / CSV): shared settings plus its ordered list of cases. All cases of a
 * file share one variable scope, so a value extracted by an early request (an id, a token) is
 * available to the later ones.
 */
public final class SpecFile {

    private final Path source;
    private final String name;
    private final Map<String, Object> settings;
    private final Map<String, Object> vars = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<ApiCase> cases = new ArrayList<>();
    private final Set<String> declaredExtracts = new LinkedHashSet<>();
    private final Map<String, Object> runtime = new ConcurrentHashMap<>();

    SpecFile(Path source, String name, Map<String, Object> settings) {
        this.source = source;
        this.name = name;
        this.settings = settings;
    }

    public Path source() {
        return source;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> settings() {
        return settings;
    }

    /** Live variable scope shared by every case in this file. */
    public Map<String, Object> vars() {
        return vars;
    }

    public List<ApiCase> cases() {
        return Collections.unmodifiableList(cases);
    }

    void addCase(ApiCase c) {
        cases.add(c);
        declaredExtracts.addAll(c.extractNames());
    }

    /** True if some case in this file extracts a variable with this name. */
    public boolean isExtractedSomewhere(String variable) {
        return declaredExtracts.contains(variable);
    }

    /** Per-run scratch space (cached login token, cleanup list, ...). */
    public Map<String, Object> runtime() {
        return runtime;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = settings.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    public String string(String key) {
        Object v = settings.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Set<String> defaultTags() {
        Object t = settings.get("tags");
        Set<String> out = new LinkedHashSet<>();
        if (t instanceof List<?> list) {
            list.forEach(x -> out.add(String.valueOf(x).trim().toLowerCase(java.util.Locale.ROOT)));
        }
        return out;
    }
}
