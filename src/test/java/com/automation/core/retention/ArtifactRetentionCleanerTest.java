package com.automation.core.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleaner deletes files, so every rule is checked against real temp files whose modified
 * times are set explicitly. A fixed base time keeps the tests independent of the clock.
 */
class ArtifactRetentionCleanerTest {

    private static final Instant BASE = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration GAP = Duration.ofMinutes(30);

    @Test
    void keepsTheNewestRunAndDeletesOlderOnes(@TempDir Path dir) throws IOException {
        Path newA = file(dir, "new-a.avi", BASE);
        Path newB = file(dir, "new-b.avi", BASE.minus(Duration.ofMinutes(5)));
        Path mid = file(dir, "mid.avi", BASE.minus(Duration.ofHours(3)));
        Path old = file(dir, "old.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertTrue(Files.exists(newA));
        assertTrue(Files.exists(newB));
        assertFalse(Files.exists(mid));
        assertFalse(Files.exists(old));
    }

    @Test
    void keepsAsManyRunsAsRequested(@TempDir Path dir) throws IOException {
        Path run1 = file(dir, "run1.avi", BASE);
        Path run2 = file(dir, "run2.avi", BASE.minus(Duration.ofHours(3)));
        Path run3 = file(dir, "run3.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 2, GAP, ".avi", false);

        assertTrue(Files.exists(run1));
        assertTrue(Files.exists(run2));
        assertFalse(Files.exists(run3));
    }

    @Test
    void keepingMoreRunsThanExistDeletesNothing(@TempDir Path dir) throws IOException {
        Path run1 = file(dir, "run1.avi", BASE);
        Path run2 = file(dir, "run2.avi", BASE.minus(Duration.ofHours(3)));

        ArtifactRetentionCleaner.prune(dir, 10, GAP, ".avi", false);

        assertTrue(Files.exists(run1));
        assertTrue(Files.exists(run2));
    }

    @Test
    void filesExactlyOneGapApartBelongToTheSameRun(@TempDir Path dir) throws IOException {
        Path first = file(dir, "first.avi", BASE);
        Path second = file(dir, "second.avi", BASE.minus(GAP));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void filesJustOverOneGapApartAreSeparateRuns(@TempDir Path dir) throws IOException {
        Path first = file(dir, "first.avi", BASE);
        Path second = file(dir, "second.avi", BASE.minus(GAP).minusSeconds(1));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertTrue(Files.exists(first));
        assertFalse(Files.exists(second));
    }

    @Test
    void aLongRunIsNotSplitWhileEachFileIsWithinTheGapOfTheNext(@TempDir Path dir)
        throws IOException {
        // 0, 25, 50, 75 minutes back: 75 minutes in total, but never more than 25 between files.
        Path[] files = new Path[4];
        for (int i = 0; i < files.length; i++) {
            files[i] = file(dir, "f" + i + ".avi", BASE.minus(Duration.ofMinutes(25L * i)));
        }

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        for (Path f : files) {
            assertTrue(Files.exists(f), f.toString());
        }
    }

    @Test
    void dryRunReportsButDeletesNothing(@TempDir Path dir) throws IOException {
        Path newest = file(dir, "newest.avi", BASE);
        Path old = file(dir, "old.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", true);

        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(old));
    }

    @Test
    void onlyFilesWithTheGivenExtensionAreTouched(@TempDir Path dir) throws IOException {
        Path oldVideo = file(dir, "old.avi", BASE.minus(Duration.ofHours(6)));
        Path newVideo = file(dir, "new.avi", BASE);
        Path oldNote = file(dir, "old-notes.txt", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertFalse(Files.exists(oldVideo));
        assertTrue(Files.exists(newVideo));
        assertTrue(Files.exists(oldNote));
    }

    @Test
    void subDirectoriesAreNotEntered(@TempDir Path dir) throws IOException {
        Path newest = file(dir, "newest.avi", BASE);
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Path nested = file(sub, "nested-old.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(nested));
    }

    @Test
    void missingDirectoryIsANoOp(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there");

        ArtifactRetentionCleaner.prune(missing, 1, GAP, ".avi", false);

        assertFalse(Files.exists(missing));
    }

    @Test
    void directoryWithNoMatchingFilesIsLeftAlone(@TempDir Path dir) throws IOException {
        Path note = file(dir, "notes.txt", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.prune(dir, 1, GAP, ".avi", false);

        assertTrue(Files.exists(note));
    }

    @Test
    void keepRunsBelowOneIsRejectedBeforeAnythingIsDeleted(@TempDir Path dir) throws IOException {
        Path only = file(dir, "only.avi", BASE);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ArtifactRetentionCleaner.prune(dir, 0, GAP, ".avi", false));

        assertTrue(ex.getMessage().contains("--keep-runs must be at least 1"), ex.getMessage());
        assertTrue(Files.exists(only));
    }

    @Test
    void mainParsesItsOptionsAndHonoursDryRun(@TempDir Path dir) throws IOException {
        Path newest = file(dir, "newest.avi", BASE);
        Path old = file(dir, "old.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.main(new String[] {
            "--dir", dir.toString(), "--keep-runs", "1", "--gap-minutes", "30",
            "--extension", ".avi", "--dry-run", "true"});

        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(old));
    }

    @Test
    void mainDeletesWhenDryRunIsFalse(@TempDir Path dir) throws IOException {
        Path newest = file(dir, "newest.avi", BASE);
        Path old = file(dir, "old.avi", BASE.minus(Duration.ofHours(6)));

        ArtifactRetentionCleaner.main(new String[] {
            "--dir", dir.toString(), "--keep-runs", "1", "--dry-run", "false"});

        assertTrue(Files.exists(newest));
        assertFalse(Files.exists(old));
    }

    @Test
    void mainRejectsAnUnknownOption() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ArtifactRetentionCleaner.main(new String[] {"--bogus"}));

        assertEquals("Unknown argument: --bogus"
            + " (expected --dir, --keep-runs, --gap-minutes, --extension, --dry-run)",
            ex.getMessage());
    }

    private static Path file(Path dir, String name, Instant modified) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, "x");
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }
}
