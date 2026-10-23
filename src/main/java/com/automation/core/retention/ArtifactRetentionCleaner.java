package com.automation.core.retention;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prunes accumulated {@code target/videos/*.avi} (or any similarly flat, timestamp-named
 * artifact directory) down to the last N test <em>runs</em>, not the last N files.
 *
 * <p>{@code VideoRecorder} writes one file per test attempt, flat in {@code target/videos/}
 * (no per-run subfolder — see its own {@code createMovieFile} override), so a single local
 * {@code mvn test} invocation with {@code video.enabled=true} can drop anywhere from one file
 * (a smoke run) to a few hundred (a full regression run with several failures). Nothing ever
 * deletes these between invocations, and unlike the CI side — where GitHub Actions'
 * {@code retention-days: 7} on the uploaded artifact and Jenkins' {@code buildDiscarder}
 * already bound growth — a developer's own {@code target/} is never touched by any of that,
 * so it only ever grows across the lifetime of the checkout.
 *
 * <p>Because files carry no run identifier, "last N runs" is reconstructed here by clustering
 * files by last-modified time: any two files less than {@code gapMinutes} apart belong to the
 * same run, and a gap larger than that starts a new one. This is a heuristic, not a guarantee —
 * a single run that pauses on a slow test for longer than the gap could get split into two
 * clusters — but for local dev use (deleting only, never anything CI depends on) an occasional
 * over-cautious split just means one extra run's worth of files gets kept a little longer than
 * strictly necessary, which is a safe direction to be wrong in.
 *
 * <p>Invoked via {@code mvn exec:java@prune-artifacts} (see the matching pom.xml profile) or,
 * more conveniently, {@code Scripts/prune-artifacts.sh}. Deliberately dependency-free (JDK
 * {@code java.nio}/{@code java.time} only) for the same reason as {@code com.automation.core.tia}
 * and {@code com.automation.core.coverage} — this runs against already-compiled classes via
 * {@code exec:java}, so it can't assume any other dependency has been resolved.
 */
public final class ArtifactRetentionCleaner {

    private ArtifactRetentionCleaner() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("target/videos");
        int keepRuns = 5;
        int gapMinutes = 30;
        String extension = ".avi";
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(args[++i]);
                case "--keep-runs" -> keepRuns = Integer.parseInt(args[++i]);
                case "--gap-minutes" -> gapMinutes = Integer.parseInt(args[++i]);
                case "--extension" -> extension = args[++i];
                // Takes an explicit true/false value (not a bare flag) so it can be wired
                // through a Maven property (see the prune-artifacts profile in pom.xml) —
                // exec-maven-plugin has no way to conditionally omit an <argument> element,
                // so the property must always resolve to *something*, and "--dry-run false"
                // has to be a valid, harmless thing to pass.
                case "--dry-run" -> dryRun = Boolean.parseBoolean(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]
                    + " (expected --dir, --keep-runs, --gap-minutes, --extension, --dry-run)");
            }
        }

        prune(dir, keepRuns, Duration.ofMinutes(gapMinutes), extension, dryRun);
    }

    /**
     * A "run" is a maximal run of files whose last-modified timestamps are each within
     * {@code gap} of the next-most-recent file in the group.
     */
    record Run(List<Path> files, Instant mostRecent) {
    }

    public static void prune(Path dir, int keepRuns, Duration gap, String extension, boolean dryRun)
        throws IOException {
        String label = "[artifact-retention]";

        if (!Files.isDirectory(dir)) {
            System.out.println(label + " " + dir.toAbsolutePath() + " does not exist — nothing to prune.");
            return;
        }
        if (keepRuns < 1) {
            throw new IllegalArgumentException("--keep-runs must be at least 1 (got " + keepRuns + ")");
        }

        List<FileEntry> files;
        try (Stream<Path> walk = Files.list(dir)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(extension))
                .map(FileEntry::of)
                .sorted(Comparator.comparing(FileEntry::modified).reversed())
                .toList();
        }

        if (files.isEmpty()) {
            System.out.println(label + " No *" + extension + " files under " + dir.toAbsolutePath()
                + " — nothing to prune.");
            return;
        }

        List<Run> runs = clusterIntoRuns(files, gap);

        System.out.println(label + " Found " + files.size() + " file(s) under " + dir.toAbsolutePath()
            + " across " + runs.size() + " run(s) (gap threshold: " + gap.toMinutes() + "m).");

        long freedBytes = 0;
        int deletedCount = 0;
        int keptRunCount = Math.min(keepRuns, runs.size());

        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            if (i < keptRunCount) {
                System.out.println(label + "   keeping run " + (i + 1) + "/" + runs.size()
                    + " (" + run.files().size() + " file(s), most recent " + run.mostRecent() + ")");
                continue;
            }
            System.out.println(label + "   " + (dryRun ? "would delete" : "deleting") + " run "
                + (i + 1) + "/" + runs.size() + " (" + run.files().size() + " file(s), most recent "
                + run.mostRecent() + ")");
            for (Path file : run.files()) {
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    size = 0;
                }
                if (!dryRun) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        System.out.println(label + "     WARNING: could not delete " + file + ": "
                            + e.getMessage());
                        continue;
                    }
                }
                freedBytes += size;
                deletedCount++;
            }
        }

        System.out.println(label + " " + (dryRun ? "Would free " : "Freed ") + humanReadable(freedBytes)
            + " across " + deletedCount + " file(s) (kept " + keptRunCount + "/" + runs.size()
            + " most recent run(s), " + (files.size() - deletedCount) + " file(s) remaining).");
    }

    /** Files must already be sorted most-recent-first. */
    private static List<Run> clusterIntoRuns(List<FileEntry> filesNewestFirst, Duration gap) {
        List<Run> runs = new ArrayList<>();
        List<Path> current = new ArrayList<>();
        Instant currentMostRecent = null;
        Instant lastSeen = null;

        for (FileEntry entry : filesNewestFirst) {
            if (lastSeen == null || Duration.between(entry.modified(), lastSeen).compareTo(gap) <= 0) {
                if (current.isEmpty()) {
                    currentMostRecent = entry.modified();
                }
                current.add(entry.path());
            } else {
                runs.add(new Run(new ArrayList<>(current), currentMostRecent));
                current.clear();
                current.add(entry.path());
                currentMostRecent = entry.modified();
            }
            lastSeen = entry.modified();
        }
        if (!current.isEmpty()) {
            runs.add(new Run(current, currentMostRecent));
        }
        return runs;
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private record FileEntry(Path path, Instant modified) {
        static FileEntry of(Path path) {
            try {
                return new FileEntry(path, Files.getLastModifiedTime(path).toInstant());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
