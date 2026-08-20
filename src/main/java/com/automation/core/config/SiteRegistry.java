package com.automation.core.config;

import com.automation.core.exceptions.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

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
 *
 * validate(site) also enforces pipeline-config.properties (repo root) —
 * the master on/off switch for whether a *registered, fully-scaffolded*
 * site is actually allowed to run right now. KNOWN_SITES answers "does
 * this site exist and is it wired up correctly"; isEnabled(site)/
 * pipeline-config.properties answers "should it run today". The same
 * file gates GitHub Actions, Jenkins, and GitLab CI via
 * Scripts/enabled-sites.sh, so disabling a site there disables it here
 * too — one flip, no pipeline or Java edits needed.
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
        // SAHMAT.properties, so this one does need the repo file.
        // (Site key is "SAHMAT" — matches config/SAHMAT.properties,
        // objectrepository/SAHMAT.properties, and the
        // com.automation.sites.sahmat Java package, all case-consistently.)
        "SAHMAT", new SiteDefinition(true)
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

        if (!Files.exists(Path.of("pipeline-config.properties"))) {
            throw new ConfigException(
                "pipeline-config.properties is missing from the repo root. This is the "
                    + "master site on/off switch shared by GitHub Actions, Jenkins, "
                    + "GitLab CI, and local runs — restore it (see git history) before "
                    + "running any site."
            );
        }

        if (!isEnabled(site)) {
            throw new ConfigException(
                "Site '" + site + "' is disabled in pipeline-config.properties "
                    + "(site." + site + ".enabled=false, or the line is missing "
                    + "entirely). This is the master on/off switch shared by GitHub "
                    + "Actions, Jenkins, GitLab CI, and local runs alike — flip it to "
                    + "true there to run this site, rather than overriding it here."
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

    /**
     * Whether {@code site} is turned on in pipeline-config.properties (repo
     * root) — the single master switch also read by Scripts/enabled-sites.sh
     * (GitHub Actions' matrix-setup, the Jenkinsfile's "Discover Site
     * Projects" stage, and GitLab CI's generate-pipeline-config job). A
     * site with no matching line, or an unreadable/missing config file,
     * is treated as disabled — fail closed rather than silently running
     * something no CI system would have scheduled.
     */
    public static boolean isEnabled(String site) {
        Properties props = loadPipelineConfig();
        return "true".equals(props.getProperty("site." + site + ".enabled"));
    }

    private static Properties loadPipelineConfig() {
        Properties props = new Properties();
        // pipeline-config.properties lives at the repo root, not on the
        // classpath (unlike config/{site}.properties under
        // src/test/resources) — every CI system and a local `mvn test`
        // alike run with the repo root as the working directory, so a
        // plain relative path resolves the same way everywhere.
        Path path = Path.of("pipeline-config.properties");
        if (!Files.exists(path)) {
            return props;
        }
        try (InputStream input = Files.newInputStream(path)) {
            props.load(input);
        } catch (IOException e) {
            // Fails closed (empty Properties -> isEnabled() returns false for
            // everything) rather than throwing here — a missing/unreadable
            // config file naming which site is affected is a much clearer
            // error, produced by validate() immediately above, than an IO
            // stack trace out of this loader.
            return new Properties();
        }
        return props;
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
