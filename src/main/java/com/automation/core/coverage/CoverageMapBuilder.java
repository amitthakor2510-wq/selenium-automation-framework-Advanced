package com.automation.core.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Turns the per-test-class {@code .exec} files {@code JacocoPerTestCoverageListener} dumps
 * (one file per test class, named {@code <TestClassFqcn>.exec}) into the single plain-text file
 * {@code com.automation.core.tia.CoverageMap} reads: {@code target/tia/coverage-map.txt}, one
 * {@code testFqcn<TAB>coveredClassFqcn} pair per line.
 *
 * <p>Plain text, not JaCoCo's binary format or anything JSON — deliberately, so
 * {@code com.automation.core.tia} (which this file exists to feed) never has to depend on
 * {@code org.jacoco.core} or any other library itself; see that package's own javadoc.
 *
 * <p>Invoked via {@code mvn exec:java@coverage-map} (see the matching pom.xml profile) after a
 * serial, JMX-enabled capture run — see {@code Scripts/build-coverage-map.sh} for the full
 * sequence, and {@code TEST_IMPACT_ANALYSIS.md} ("Coverage-based fallback") for the design.
 */
public final class CoverageMapBuilder {

    /** Written by {@code JacocoPerTestCoverageListener} if it ever saw more than one test class
     *  executing concurrently — see that class's javadoc. Its presence means the exec files in
     *  the same directory can't be trusted to reflect one test class each, so this refuses to
     *  build a map from them at all rather than silently emit cross-contaminated data. */
    static final String UNRELIABLE_MARKER = "UNRELIABLE.marker";

    private CoverageMapBuilder() {
    }

    public static void main(String[] args) throws IOException {
        Path execDir = args.length > 0 ? Path.of(args[0]) : Path.of("target/jacoco-per-test");
        Path outputFile = args.length > 1 ? Path.of(args[1]) : Path.of("target/tia/coverage-map.txt");
        build(execDir, outputFile);
    }

    public static void build(Path execDir, Path outputFile) throws IOException {
        if (!Files.isDirectory(execDir)) {
            System.out.println("[coverage-map] " + execDir.toAbsolutePath() + " does not exist — nothing "
                + "to build. Did the capture run (Scripts/build-coverage-map.sh) run first? Check that "
                + "run's console output for \"[coverage-capture]\" lines — JacocoPerTestCoverageListener "
                + "now logs whether the JMX MBean was found and how many .exec files it wrote, which "
                + "pinpoints whether capture ran at all versus ran but wrote somewhere unexpected.");
            return;
        }
        if (Files.exists(execDir.resolve(UNRELIABLE_MARKER))) {
            String reason;
            try {
                reason = Files.readString(execDir.resolve(UNRELIABLE_MARKER), StandardCharsets.UTF_8);
            } catch (IOException e) {
                reason = "(could not read marker file: " + e.getMessage() + ")";
            }
            System.out.println("[coverage-map] Refusing to build a map: " + execDir
                + " is marked UNRELIABLE. Reason:\n  " + reason.strip()
                + "\nNo coverage-map.txt written — TestImpactAnalyzer will fall back to "
                + "bytecode-only analysis, exactly as if no coverage map existed at all.");
            return;
        }

        List<Path> execFiles;
        try (Stream<Path> walk = Files.list(execDir)) {
            execFiles = walk.filter(p -> p.toString().endsWith(".exec")).sorted().toList();
        }
        if (execFiles.isEmpty()) {
            System.out.println("[coverage-map] No .exec files found under " + execDir.toAbsolutePath()
                + " — nothing to build.");
            return;
        }

        // TreeMap/TreeSet purely so the output file is stable/diff-friendly across runs.
        TreeMap<String, TreeSet<String>> byTest = new TreeMap<>();
        int skipped = 0;
        for (Path execFile : execFiles) {
            String fileName = execFile.getFileName().toString();
            String testFqcn = fileName.substring(0, fileName.length() - ".exec".length());
            Set<String> touched;
            try {
                touched = CoverageExecReader.touchedClasses(execFile);
            } catch (IOException e) {
                System.out.println("[coverage-map] Skipping unreadable file " + execFile + ": " + e.getMessage());
                skipped++;
                continue;
            }
            TreeSet<String> covered = byTest.computeIfAbsent(testFqcn, k -> new TreeSet<>());
            for (String fqcn : touched) {
                // Scoped to this project's own namespace — JaCoCo's includes/excludes already
                // limit instrumentation mostly to this, but filtering here too keeps the map
                // small and focused regardless of how that's configured, and drops the
                // trivial/noisy "test observed itself" entry along with it.
                if (fqcn.startsWith("com.automation.") && !fqcn.equals(testFqcn)) {
                    covered.add(fqcn);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        int pairCount = 0;
        for (var entry : byTest.entrySet()) {
            for (String covered : entry.getValue()) {
                sb.append(entry.getKey()).append('\t').append(covered).append('\n');
                pairCount++;
            }
        }

        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        System.out.println("[coverage-map] Wrote " + outputFile + ": " + byTest.size() + " test classes, "
            + pairCount + " test-to-class observations"
            + (skipped > 0 ? " (" + skipped + " exec file(s) skipped, see above)" : "") + ".");
    }
}
