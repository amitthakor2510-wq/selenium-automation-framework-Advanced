package com.automation.core.tia;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a site key (as used by {@code -Dsite=...} / {@code SiteRegistry}) to the Java package
 * that holds its tests, and back. Mirrors {@code SiteRegistry.KNOWN_SITES} — kept as a small,
 * separate, dependency-free list here rather than reflectively reading that class, since TIA
 * needs to run from plain compiled {@code .class} files without pulling in the rest of the
 * framework (see {@code TEST_IMPACT_ANALYSIS.md}). Update both places together when a new site
 * is added (the same checklist {@code SiteRegistry}'s own javadoc already describes).
 */
public final class SiteMapper {

    private static final Map<String, String> SITE_TEST_PACKAGE = new LinkedHashMap<>();

    static {
        SITE_TEST_PACKAGE.put("demoqa", "com.automation.sites.demoqa");
        SITE_TEST_PACKAGE.put("saucedemo", "com.automation.sites.saucedemo");
        SITE_TEST_PACKAGE.put("mobile", "com.automation.mobile");
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
