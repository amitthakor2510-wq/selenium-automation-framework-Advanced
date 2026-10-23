package com.automation.core.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filesystem-only unit tests for {@link DriverCleanup} (no browser needed).
 *
 * <p>The JVM-exit shutdown hook itself cannot be observed from inside the
 * same JVM, so the deletion logic it runs ({@code deleteRecursively}) is
 * tested directly.
 */
class DriverCleanupTest {

    @TempDir
    Path tmp;

    private Path sampleProfile() throws IOException {
        Path profile = Files.createDirectories(tmp.resolve("chrome-profile"));
        Files.writeString(profile.resolve("Preferences"), "{}");
        Files.createDirectories(profile.resolve("Default/Cache/nested"));
        Files.writeString(profile.resolve("Default/Cache/nested/data_0"), "cached bytes");
        Files.writeString(profile.resolve("Default/Cookies"), "cookie jar");
        Files.createDirectories(profile.resolve("EmptyDir"));
        return profile;
    }

    @Test
    void deleteRecursivelyRemovesTheWholeTempProfileTree() throws IOException {
        Path profile = sampleProfile();
        assertTrue(Files.exists(profile.resolve("Default/Cache/nested/data_0")));

        DriverCleanup.deleteRecursively(profile.toFile());

        assertFalse(Files.exists(profile), "profile directory should be gone");
    }

    @Test
    void deleteRecursivelyLeavesSiblingDirectoriesAlone() throws IOException {
        Path profile = sampleProfile();
        Path sibling = Files.createDirectories(tmp.resolve("other-profile"));
        Files.writeString(sibling.resolve("keep.txt"), "keep me");

        DriverCleanup.deleteRecursively(profile.toFile());

        assertTrue(Files.exists(sibling.resolve("keep.txt")), "unrelated directories must not be touched");
    }

    @Test
    void deleteRecursivelyHandlesASingleFile() throws IOException {
        Path file = Files.writeString(tmp.resolve("lonely.lock"), "x");

        DriverCleanup.deleteRecursively(file.toFile());

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteRecursivelyIgnoresNullAndMissingPaths() {
        assertDoesNotThrow(() -> DriverCleanup.deleteRecursively(null));
        assertDoesNotThrow(() -> DriverCleanup.deleteRecursively(new File(tmp.toFile(), "does-not-exist")));
    }

    @Test
    void quitDriverIsSafeForANullDriver() {
        assertDoesNotThrow(() -> DriverCleanup.quitDriver(null));
    }

    @Test
    void registerTempProfileCleanupCanBeCalledRepeatedly() throws IOException {
        Path first = Files.createDirectories(tmp.resolve("p1"));
        Path second = Files.createDirectories(tmp.resolve("p2"));

        // The shutdown hook is registered once; later calls only add to the list.
        assertDoesNotThrow(() -> DriverCleanup.registerTempProfileCleanup(first));
        assertDoesNotThrow(() -> DriverCleanup.registerTempProfileCleanup(second));
    }
}
