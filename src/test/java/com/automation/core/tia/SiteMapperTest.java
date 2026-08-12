package com.automation.core.tia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteMapperTest {

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
