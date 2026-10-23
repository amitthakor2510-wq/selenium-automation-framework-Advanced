package com.automation.core.tia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageMapTest {

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path repoRoot) {
        CoverageMap map = CoverageMap.loadIfPresent(repoRoot);
        assertTrue(map.isEmpty());
        assertTrue(map.testsThatObserved("com.automation.core.util.StringHelper").isEmpty());
    }

    @Test
    void loadsTabSeparatedPairsAndInvertsToClassToTests(@TempDir Path repoRoot) throws Exception {
        writeCoverageMap(repoRoot,
            "com.automation.sites.demoqa.tests.ButtonsTest\tcom.automation.core.util.StringHelper\n"
                + "com.automation.sites.saucedemo.tests.LoginTest\tcom.automation.core.util.StringHelper\n"
                + "com.automation.sites.saucedemo.tests.LoginTest\tcom.automation.core.config.ConfigReader\n");

        CoverageMap map = CoverageMap.loadIfPresent(repoRoot);

        assertTrue(!map.isEmpty());
        assertEquals(Set.of(
            "com.automation.sites.demoqa.tests.ButtonsTest",
            "com.automation.sites.saucedemo.tests.LoginTest"
        ), map.testsThatObserved("com.automation.core.util.StringHelper"));
        assertEquals(Set.of("com.automation.sites.saucedemo.tests.LoginTest"),
            map.testsThatObserved("com.automation.core.config.ConfigReader"));
    }

    @Test
    void classWithNoObservationsReturnsEmptySet(@TempDir Path repoRoot) throws Exception {
        writeCoverageMap(repoRoot, "com.automation.sites.demoqa.tests.ButtonsTest\tcom.automation.core.util.StringHelper\n");

        CoverageMap map = CoverageMap.loadIfPresent(repoRoot);

        assertTrue(map.testsThatObserved("com.automation.core.util.NeverObserved").isEmpty());
    }

    @Test
    void malformedLinesAreSkippedNotFatal(@TempDir Path repoRoot) throws Exception {
        writeCoverageMap(repoRoot,
            "not-a-valid-line-no-tab\n"
                + "\n"
                + "com.automation.sites.demoqa.tests.ButtonsTest\tcom.automation.core.util.StringHelper\n");

        CoverageMap map = CoverageMap.loadIfPresent(repoRoot);

        assertEquals(Set.of("com.automation.sites.demoqa.tests.ButtonsTest"),
            map.testsThatObserved("com.automation.core.util.StringHelper"));
    }

    private static void writeCoverageMap(Path repoRoot, String content) throws Exception {
        Path file = repoRoot.resolve("target/tia/coverage-map.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
