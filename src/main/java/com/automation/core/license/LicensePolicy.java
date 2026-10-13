package com.automation.core.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What is allowed, what is forbidden, and what to do about the grey area. Loaded from
 * {@code license-policy.properties} (repo root); every key can be overridden with {@code -Dkey=value}.
 * With no file at all the built-in defaults below apply, so the scanner works out of the box.
 *
 * <p>Semantics (Maven treats several {@code <license>} entries as alternatives - "pick one"):
 * a dependency is {@link LicenseStatus#ALLOWED} if ANY declared license is allowed,
 * {@link LicenseStatus#DENIED} only if ALL of them are denied, otherwise {@link LicenseStatus#REVIEW}.
 */
public final class LicensePolicy {

    /** What a finding of a given kind does to the result. */
    public enum Action { IGNORE, WARN, FAIL }

    static final String DEFAULT_ALLOWED = "Apache-2.0,MIT,BSD-2-Clause,BSD-3-Clause,ISC,EPL-1.0,EPL-2.0,"
        + "CDDL-1.0,CDDL-1.1,MPL-2.0,GPL-2.0-with-classpath-exception,GPL-3.0-with-classpath-exception,"
        + "CC0-1.0,Public-Domain,Unlicense,Zlib";
    static final String DEFAULT_DENIED = "GPL-2.0,GPL-3.0,AGPL-3.0,SSPL-1.0,BUSL-1.1,Commons-Clause,JSON,"
        + "BSD-4-Clause";

    private final Set<String> allowed;
    private final Set<String> denied;
    private final Action reviewAction;
    private final Action missingAction;
    private final Action deniedAction;
    private final List<String> ignore;
    private final Map<String, List<String>> overrides;

    LicensePolicy(Set<String> allowed, Set<String> denied, Action deniedAction, Action reviewAction,
                  Action missingAction, List<String> ignore, Map<String, List<String>> overrides) {
        this.allowed = allowed;
        this.denied = denied;
        this.deniedAction = deniedAction;
        this.reviewAction = reviewAction;
        this.missingAction = missingAction;
        this.ignore = ignore;
        this.overrides = overrides;
    }

    /** Built-in defaults only (no file, no system-property overrides). */
    public static LicensePolicy defaults() {
        return fromProperties(new Properties(), k -> null);
    }

    /**
     * Loads {@code file} when it exists (a missing file means "use the defaults"), then lets
     * {@code -D} system properties override individual keys.
     */
    public static LicensePolicy load(Path file) throws IOException {
        Properties props = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return fromProperties(props, System::getProperty);
    }

    /** {@code overrideLookup} is consulted first for every key (system properties in production). */
    static LicensePolicy fromProperties(Properties props, UnaryOperator<String> overrideLookup) {
        Set<String> allowed = ids(value(props, overrideLookup, "license.allowed", DEFAULT_ALLOWED));
        Set<String> denied = ids(value(props, overrideLookup, "license.denied", DEFAULT_DENIED));
        Action deniedAction = action(value(props, overrideLookup, "license.denied.action", "FAIL"), Action.FAIL);
        Action reviewAction = action(value(props, overrideLookup, "license.review.action", "WARN"), Action.WARN);
        Action missingAction = action(value(props, overrideLookup, "license.missing.action", "WARN"), Action.WARN);
        List<String> ignore = new ArrayList<>(split(value(props, overrideLookup, "license.ignore", "")));
        Map<String, List<String>> overrides = parseOverrides(value(props, overrideLookup, "license.overrides", ""));
        return new LicensePolicy(allowed, denied, deniedAction, reviewAction, missingAction, ignore, overrides);
    }

    private static String value(Properties props, UnaryOperator<String> overrideLookup, String key, String dflt) {
        String o = overrideLookup.apply(key);
        if (o != null && !o.isBlank()) {
            return o;
        }
        return props.getProperty(key, dflt);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split("[,;]")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static Set<String> ids(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : split(csv)) {
            out.add(id.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Action action(String raw, Action fallback) {
        try {
            return Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** {@code group:artifact=MIT|Apache-2.0, group:*=MIT} -> map keyed by "group:artifact". */
    private static Map<String, List<String>> parseOverrides(String raw) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String entry : split(raw)) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                continue;
            }
            String key = entry.substring(0, eq).trim();
            List<String> licenses = Arrays.stream(entry.substring(eq + 1).split("\\|"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!licenses.isEmpty()) {
                map.put(key, licenses);
            }
        }
        return map;
    }

    /** True when {@code group:artifact} matches an entry of {@code license.ignore}. */
    public boolean isIgnored(String groupId, String artifactId) {
        return ignore.stream().anyMatch(p -> matches(p, groupId, artifactId));
    }

    /** The identifiers a manual override assigns to {@code group:artifact}, or an empty list. */
    public List<String> overrideFor(String groupId, String artifactId) {
        List<String> exact = overrides.get(groupId + ":" + artifactId);
        if (exact != null) {
            return exact;
        }
        return overrides.getOrDefault(groupId + ":*", List.of());
    }

    private static boolean matches(String pattern, String groupId, String artifactId) {
        int colon = pattern.indexOf(':');
        if (colon < 0) {
            return pattern.equals(groupId);
        }
        String g = pattern.substring(0, colon);
        String a = pattern.substring(colon + 1);
        return g.equals(groupId) && (a.equals("*") || a.equals(artifactId));
    }

    /** Decides the status for a dependency whose normalised license ids are {@code ids} (empty = none found). */
    public LicenseStatus classify(List<String> ids) {
        if (ids.isEmpty()) {
            return LicenseStatus.MISSING;
        }
        if (ids.stream().anyMatch(i -> allowed.contains(i.toLowerCase(Locale.ROOT)))) {
            return LicenseStatus.ALLOWED;
        }
        if (ids.stream().allMatch(i -> denied.contains(i.toLowerCase(Locale.ROOT)))) {
            return LicenseStatus.DENIED;
        }
        return LicenseStatus.REVIEW;
    }

    /** What {@code status} does to the overall result under this policy. */
    public Action actionFor(LicenseStatus status) {
        return switch (status) {
            case DENIED -> deniedAction;
            case REVIEW -> reviewAction;
            case MISSING -> missingAction;
            case ALLOWED -> Action.IGNORE;
        };
    }

    public Set<String> allowedIds() {
        return allowed;
    }

    public Set<String> deniedIds() {
        return denied;
    }
}
