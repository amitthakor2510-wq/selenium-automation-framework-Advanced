package com.automation.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drift guard for the "register a site in several places" workflow
 * (Scripts/new-site.sh, Scripts/new-api-site.sh). A site present in
 * {@link SiteRegistry} but missing its config file, or its
 * pipeline-config.properties switch, only fails at runtime with a
 * ConfigException on the first run of that site; this catches it at build
 * time instead. Reads files relative to the repo root (the working directory
 * for Maven and every CI system, same assumption SiteRegistry itself makes).
 */
class SiteRegistryConsistencyTest {

    @Test
    void everyRegisteredSiteHasAConfigFile() {
        assertAll(SiteRegistry.knownSiteKeys().stream().sorted().map(site -> () ->
            assertTrue(Files.exists(Path.of("src/test/resources/config/" + site + ".properties")),
                "Site '" + site + "' is in SiteRegistry.KNOWN_SITES but "
                    + "src/test/resources/config/" + site + ".properties does not exist")));
    }

    @Test
    void everyRegisteredSiteHasAPipelineConfigSwitch() throws IOException {
        Properties pipeline = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("pipeline-config.properties"))) {
            pipeline.load(in);
        }
        assertAll(SiteRegistry.knownSiteKeys().stream().sorted().map(site -> () -> {
            String value = pipeline.getProperty("site." + site + ".enabled");
            assertTrue("true".equals(value) || "false".equals(value),
                "Site '" + site + "' has no site." + site + ".enabled=true|false line in "
                    + "pipeline-config.properties (found: " + value + "). A missing line is "
                    + "treated as disabled by SiteRegistry.isEnabled(), so CI would silently skip it.");
        }));
    }

    @Test
    void pipelineConfigDoesNotReferenceUnregisteredSites() throws IOException {
        Properties pipeline = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("pipeline-config.properties"))) {
            pipeline.load(in);
        }
        TreeSet<String> orphans = new TreeSet<>();
        for (String key : pipeline.stringPropertyNames()) {
            if (key.startsWith("site.") && key.endsWith(".enabled")) {
                String site = key.substring("site.".length(), key.length() - ".enabled".length());
                if (!SiteRegistry.knownSiteKeys().contains(site)) {
                    orphans.add(site);
                }
            }
        }
        if (!orphans.isEmpty()) {
            fail("pipeline-config.properties enables site(s) " + orphans
                + " that are not in SiteRegistry.KNOWN_SITES — CI would schedule them and "
                + "every run would fail validation. Register them or remove the lines.");
        }
    }
}
