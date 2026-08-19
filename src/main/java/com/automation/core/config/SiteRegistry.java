package com.automation.core.config;

import com.automation.core.exceptions.ConfigException;

import java.util.Map;

/**
 * The single source of truth for "what sites exist and what does each one
 * need". Without this, adding site #4 is convention-only: create
 * config/{site}.properties, maybe objectrepository/{site}.properties, a
 * sites/{site}/pages package, and a testng-suites/{site}-*.xml — and if you
 * forget one, nothing tells you at startup. Instead you get a confusing
 * failure deep inside a page object or ObjectRepository.get() halfway
 * through a run, far from the actual missing piece.
 *
 * SiteRegistry.validate(site) is called once from ConfigReader.init(),
 * right after the active site is resolved and before any test method runs.
 * If something's missing, it fails immediately with a message naming the
 * exact missing file and what to do about it — not an NPE three layers
 * deep in a KeywordEngine step.
 *
 * To add a new site: add one line to KNOWN_SITES below, create
 * config/{site}.properties, and (only if that site will run
 * keyword-driven tests, e.g. via KeywordTestBase) create
 * objectrepository/{site}.properties. That's the whole checklist, and
 * this class is what enforces you didn't skip a step.
 */
public final class SiteRegistry {

    /**
     * requiresObjectRepository = true means this site is expected to run
     * keyword-driven tests at some point (see KeywordTestBase), so its
     * objectrepository/{site}.properties must exist. Sites that never use
     * the keyword engine (pure page-object UI tests, or non-UI sites)
     * don't need one.
     */
    private record SiteDefinition(boolean requiresObjectRepository) {
    }

    private static final Map<String, SiteDefinition> KNOWN_SITES = Map.of(
        "demoqa", new SiteDefinition(true),
        "saucedemo", new SiteDefinition(true),
        "mobile", new SiteDefinition(false),
        // Entirely keyword-driven (login + forgot-password) — every
        // scenario is a CSV row resolved against objectrepository/
        // indiaai.properties, so this one does need the repo file.
        "indiaai", new SiteDefinition(true)
    );

    private SiteRegistry() {
    }

    /**
     * Validates that {@code site} is registered and that every resource
     * it requires is actually present on the classpath. Throws
     * ConfigException with a specific, actionable message on the first
     * problem found — never returns a partial/ambiguous failure.
     */
    public static void validate(String site) {
        SiteDefinition def = KNOWN_SITES.get(site);
        if (def == null) {
            throw new ConfigException(
                "Site '" + site + "' is not registered in SiteRegistry. Known sites: "
                    + KNOWN_SITES.keySet() + ". If this is a new site, add it to "
                    + "SiteRegistry.KNOWN_SITES first — that's step 1 of adding a site, "
                    + "before config files or page objects."
            );
        }

        String configPath = "config/" + site + ".properties";
        if (!existsOnClasspath(configPath)) {
            throw new ConfigException(
                "Site '" + site + "' is registered in SiteRegistry, but its config file is "
                    + "missing: src/test/resources/" + configPath
            );
        }

        if (def.requiresObjectRepository()) {
            String repoPath = "objectrepository/" + site + ".properties";
            if (!existsOnClasspath(repoPath)) {
                throw new ConfigException(
                    "Site '" + site + "' is registered as requiring an object repository "
                        + "(it runs keyword-driven tests), but its file is missing: "
                        + "src/test/resources/" + repoPath
                );
            }
        }
    }

    private static boolean existsOnClasspath(String path) {
        try (var input = SiteRegistry.class.getClassLoader().getResourceAsStream(path)) {
            return input != null;
        } catch (java.io.IOException e) {
            // Stream opened successfully (so the resource exists) but failed to
            // close cleanly — treat as present rather than failing validation
            // on an unrelated close error.
            return true;
        }
    }
}
