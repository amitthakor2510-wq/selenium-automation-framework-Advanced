package com.automation.core.tia;

import com.automation.core.config.SiteRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteMapperTest {

    /**
     * Regression test for a bug class that has shipped unnoticed twice
     * before (SAHMAT, then jsonplaceholder — see SiteMapper's own
     * class-level Javadoc): a site registered in SiteRegistry.KNOWN_SITES
     * but missing from SiteMapper.SITE_TEST_PACKAGE, which makes Test
     * Impact Analysis silently fall back to its site-blind "unsafe/full
     * suite" decision for every change touching that site instead of
     * scoping to just its tests — no test failure, no error message,
     * just a quietly-too-broad (or, in principle, too-narrow) test run.
     * SiteMapper deliberately doesn't depend on SiteRegistry at runtime
     * (kept dependency-free so TIA can run from plain .class files — see
     * SiteMapper's own Javadoc), so this cross-check only exists here, at
     * test time, via SiteRegistry.knownSiteKeys().
     */
    @Test
    void siteMapperStaysInSyncWithSiteRegistry() {
        assertEquals(SiteRegistry.knownSiteKeys(), SiteMapper.knownSites().keySet(),
            "SiteMapper.SITE_TEST_PACKAGE has drifted from SiteRegistry.KNOWN_SITES — "
                + "update both together (see SiteMapper's own Javadoc) whenever a site "
                + "is added, renamed, or removed.");
    }

    @Test
    void infersSiteFromPerSiteConfigFile() {
        assertEquals("demoqa", SiteMapper.siteFromResourcePath("src/test/resources/config/demoqa.properties").orElseThrow());
        assertEquals("saucedemo",
            SiteMapper.siteFromResourcePath("src/test/resources/objectrepository/saucedemo.properties").orElseThrow());
    }

    @Test
    void infersSiteFromVisualBaselineDirectory() {
        assertEquals("demoqa",
            SiteMapper.siteFromResourcePath("src/test/resources/visual-baselines/demoqa/text-box-page.png").orElseThrow());
    }

    @Test
    void infersSiteFromKeywordFilePrefix() {
        assertEquals("saucedemo",
            SiteMapper.siteFromResourcePath("src/test/resources/testdata/keyword/saucedemo_login_keywords.csv").orElseThrow());
    }

    @Test
    void globalConfigHasNoInferableSite() {
        assertTrue(SiteMapper.siteFromResourcePath("src/test/resources/config/global.properties").isEmpty());
    }

    @Test
    void belongsToSiteChecksPackagePrefix() {
        assertTrue(SiteMapper.belongsToSite("com.automation.sites.demoqa.tests.ButtonsTest", "demoqa"));
        assertFalse(SiteMapper.belongsToSite("com.automation.sites.saucedemo.tests.LoginTest", "demoqa"));
    }

    @Test
    void siteOfTestClassResolvesKnownPackages() {
        assertEquals("mobile", SiteMapper.siteOfTestClass("com.automation.mobile.sites.settings.tests.SettingsHomeTest").orElseThrow());
        assertTrue(SiteMapper.siteOfTestClass("com.automation.core.data.DataRowTest").isEmpty());
    }
}
