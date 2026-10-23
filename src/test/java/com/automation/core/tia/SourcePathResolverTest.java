package com.automation.core.tia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately narrow, pure-logic unit tests — no git process, no compiled classes, no file I/O.
 * Same shape as {@code DataRowTest}: fast, specific, and meaningful for the mutation-testing
 * profile if this package is ever added to its scope.
 */
class SourcePathResolverTest {

    @Test
    void convertsMainSourcePathToFqcn() {
        assertEquals("com.automation.core.util.StringHelper",
            SourcePathResolver.toFqcn("src/main/java/com/automation/core/util/StringHelper.java").orElseThrow());
    }

    @Test
    void convertsTestSourcePathToFqcn() {
        assertEquals("com.automation.sites.demoqa.tests.ButtonsTest",
            SourcePathResolver.toFqcn("src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java").orElseThrow());
    }

    @Test
    void nonJavaFileHasNoFqcn() {
        assertTrue(SourcePathResolver.toFqcn("src/test/resources/testdata/login.csv").isEmpty());
    }

    @Test
    void pathOutsideSourceRootsHasNoFqcn() {
        assertTrue(SourcePathResolver.toFqcn("pom.xml").isEmpty());
        assertTrue(SourcePathResolver.toFqcn("README.md").isEmpty());
    }

    @Test
    void rootOfDistinguishesMainFromTest() {
        assertEquals(SourcePathResolver.Root.MAIN,
            SourcePathResolver.rootOf("src/main/java/com/automation/core/util/StringHelper.java"));
        assertEquals(SourcePathResolver.Root.TEST,
            SourcePathResolver.rootOf("src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java"));
        assertEquals(SourcePathResolver.Root.NONE, SourcePathResolver.rootOf("pom.xml"));
    }

    @Test
    void simpleNameIsTheFileNameWithoutExtension() {
        assertEquals("ButtonsTest",
            SourcePathResolver.simpleNameOf("src/test/java/com/automation/sites/demoqa/tests/ButtonsTest.java"));
    }

    @Test
    void toFqcnRejectsEmptyRelativePath() {
        assertFalse(SourcePathResolver.toFqcn("src/main/java/.java").isPresent());
    }
}
