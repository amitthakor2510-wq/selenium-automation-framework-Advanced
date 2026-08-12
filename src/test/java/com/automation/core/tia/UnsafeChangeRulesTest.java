package com.automation.core.tia;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsafeChangeRulesTest {

    @Test
    void exactFileMatchIsUnsafe() {
        UnsafeChangeRules rules = UnsafeChangeRules.of(List.of("pom.xml"));
        assertTrue(rules.isUnsafe("pom.xml"));
        assertFalse(rules.isUnsafe("checkstyle.xml"));
    }

    @Test
    void doubleStarMatchesAnyDepthUnderADirectory() {
        UnsafeChangeRules rules = UnsafeChangeRules.of(List.of(".github/workflows/**"));
        assertTrue(rules.isUnsafe(".github/workflows/github-ci.yml"));
        assertTrue(rules.isUnsafe(".github/workflows/scripts/post_pr_comment.py"));
        assertFalse(rules.isUnsafe(".github/dependabot.yml"));
    }

    @Test
    void singleStarDoesNotCrossPathSegments() {
        UnsafeChangeRules rules = UnsafeChangeRules.of(List.of("testng-suites/*.xml"));
        assertTrue(rules.isUnsafe("testng-suites/demoqa-smoke.xml"));
        assertFalse(rules.isUnsafe("testng-suites/nested/demoqa-smoke.xml"));
    }

    @Test
    void unrelatedFileIsSafe() {
        UnsafeChangeRules rules = UnsafeChangeRules.of(UnsafeChangeRules.defaults());
        assertFalse(rules.isUnsafe("src/main/java/com/automation/core/util/StringHelper.java"));
        assertFalse(rules.isUnsafe("README.md"));
    }

    @Test
    void defaultsCoverKnownBuildAndGlobalConfigFiles() {
        UnsafeChangeRules rules = UnsafeChangeRules.of(UnsafeChangeRules.defaults());
        assertTrue(rules.isUnsafe("pom.xml"));
        assertTrue(rules.isUnsafe("checkstyle.xml"));
        assertTrue(rules.isUnsafe("testng-suites/demoqa-regression.xml"));
        assertTrue(rules.isUnsafe("Scripts/new-site.sh"));
        assertTrue(rules.isUnsafe("src/test/resources/config/global.properties"));
        assertTrue(rules.isUnsafe("src/test/resources/log4j2.xml"));
    }

    @Test
    void perSiteConfigIsNotUnsafeByDefault() {
        // Per-site config/objectrepository files are handled precisely via SiteMapper's
        // site inference instead of a blanket full-suite fallback — see TestImpactAnalyzer.
        UnsafeChangeRules rules = UnsafeChangeRules.of(UnsafeChangeRules.defaults());
        assertFalse(rules.isUnsafe("src/test/resources/config/demoqa.properties"));
        assertFalse(rules.isUnsafe("src/test/resources/objectrepository/saucedemo.properties"));
    }
}
