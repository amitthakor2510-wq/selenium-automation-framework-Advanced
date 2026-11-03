package com.automation.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The nightly "all sites enabled" CI job passes -Dpipeline.forceAllSites=true so sites that are
 * switched off in pipeline-config.properties still run. This guards the override: it must turn every
 * REGISTERED site on, never an unknown one, and must be off by default.
 */
class SiteRegistryForceAllSitesTest {

    private static final String PROPERTY = "pipeline.forceAllSites";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void forceFlagIsOffByDefault() {
        assumeTrue(System.getenv("PIPELINE_FORCE_ALL_SITES") == null,
            "environment variable is set on this machine");
        System.clearProperty(PROPERTY);
        assertFalse(SiteRegistry.forceAllSites());
    }

    @Test
    void forceFlagEnablesEveryRegisteredSite() {
        System.setProperty(PROPERTY, "true");
        assertTrue(SiteRegistry.forceAllSites());
        assertAll(SiteRegistry.knownSiteKeys().stream().sorted().map(site -> () ->
            assertTrue(SiteRegistry.isEnabled(site), "registered site '" + site + "' must be on when forced")));
    }

    @Test
    void forceFlagNeverEnablesAnUnregisteredSite() {
        System.setProperty(PROPERTY, "true");
        assertFalse(SiteRegistry.isEnabled("definitely-not-a-site"));
    }

    @Test
    void flagValueIsTrimmedAndCaseInsensitive() {
        assumeTrue(System.getenv("PIPELINE_FORCE_ALL_SITES") == null,
            "environment variable is set on this machine");
        System.setProperty(PROPERTY, " TRUE ");
        assertTrue(SiteRegistry.forceAllSites());
        System.setProperty(PROPERTY, "false");
        assertFalse(SiteRegistry.forceAllSites());
    }
}
