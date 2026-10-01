package com.automation.core.tia;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a site key (as used by {@code -Dsite=...} / {@code SiteRegistry}) to the Java package
 * that holds its tests, and back. Mirrors {@code SiteRegistry.KNOWN_SITES} — kept as a small,
 * separate, dependency-free list here rather than reflectively reading that class, since TIA
 * needs to run from plain compiled {@code .class} files without pulling in the rest of the
 * framework (see {@code docs/TEST_IMPACT_ANALYSIS.md}). Update both places together when a new site
 * is added (the same checklist {@code SiteRegistry}'s own javadoc already describes).
 */
public final class SiteMapper {

    private static final Map<String, String> SITE_TEST_PACKAGE = new LinkedHashMap<>();

    static {
        SITE_TEST_PACKAGE.put("demoqa", "com.automation.sites.demoqa");
        SITE_TEST_PACKAGE.put("saucedemo", "com.automation.sites.saucedemo");
        SITE_TEST_PACKAGE.put("mobile", "com.automation.mobile");
        // Site key is "SAHMAT" (case-sensitive, matches SiteRegistry.KNOWN_SITES,
        // config/SAHMAT.properties, and objectrepository/SAHMAT.properties) even
        // though the Java package underneath stayed lowercase "sahmat" — was
        // missing here entirely, which meant TIA silently fell back to a
        // site-blind "unsafe/full suite" decision for every SAHMAT resource
        // change instead of scoping to SAHMAT's own tests.
        SITE_TEST_PACKAGE.put("SAHMAT", "com.automation.sites.sahmat");
        // API-only site (SiteRegistry.KNOWN_SITES: requiresObjectRepository=false,
        // see docs/extending.md's "Adding a New API-Only Site" checklist) — was
        // missing here entirely, same bug class as the SAHMAT comment above: a
        // change to config/jsonplaceholder.properties or a jsonplaceholder test
        // class fell through to TIA's site-blind "unsafe/full suite" decision
        // instead of being scoped to just this site's tests.
        SITE_TEST_PACKAGE.put("jsonplaceholder", "com.automation.sites.jsonplaceholder");
    }

    private SiteMapper() {
    }

    public static Map<String, String> knownSites() {
        return SITE_TEST_PACKAGE;
    }

    public static Optional<String> testPackageFor(String site) {
        return Optional.ofNullable(SITE_TEST_PACKAGE.get(site));
    }

    /**
     * Infers a site key from a {@code config/}, {@code objectrepository/}, or
     * {@code visual-baselines/} resource path, e.g.
     * {@code "src/test/resources/config/demoqa.properties"} -&gt; {@code "demoqa"}. Empty if no
     * known site's key appears in the path (e.g. {@code global.properties}, which affects every
     * site and is handled as an unsafe/full-suite change instead — see {@link UnsafeChangeRules}).
     */
    public static Optional<String> siteFromResourcePath(String repoRelativePath) {
        for (String site : SITE_TEST_PACKAGE.keySet()) {
            if (repoRelativePath.contains("/" + site + ".")
                || repoRelativePath.contains("/" + site + "/")
                || repoRelativePath.contains(site + "_")) {
                return Optional.of(site);
            }
        }
        return Optional.empty();
    }

    /** True if {@code fqcn} lives under the given site's test package. */
    public static boolean belongsToSite(String fqcn, String site) {
        return testPackageFor(site).map(pkg -> fqcn.startsWith(pkg + ".")).orElse(false);
    }

    /** Which known site (if any) a test class belongs to, by package. */
    public static Optional<String> siteOfTestClass(String fqcn) {
        for (Map.Entry<String, String> e : SITE_TEST_PACKAGE.entrySet()) {
            if (fqcn.startsWith(e.getValue() + ".")) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
