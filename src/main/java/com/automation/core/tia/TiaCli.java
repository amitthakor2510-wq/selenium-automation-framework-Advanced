package com.automation.core.tia;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point, invoked via {@code mvn exec:java@tia} (see the {@code tia} profile
 * in {@code pom.xml}) or directly with {@code java -cp target/classes com.automation.core.tia.TiaCli}.
 * See {@code Scripts/test-impact-analysis.sh} for the wrapper that ties this together with
 * {@code mvn test}, and {@code TEST_IMPACT_ANALYSIS.md} for full usage.
 *
 * <pre>
 *   --repo-root DIR        Repo root (default: current directory)
 *   --base REF             Git ref to diff from (required)
 *   --head REF             Git ref to diff to (default: working tree, i.e. uncommitted changes included)
 *   --classes-dir DIR      Compiled classes dir; repeatable (default: target/classes, target/test-classes)
 *   --output-dir DIR       Where to write reports (default: target/tia)
 *   --no-untracked         Don't scan for brand-new, not-yet-tracked files
 * </pre>
 *
 * Exit code is always 0 on a successful analysis (FULL is a valid, successful outcome — the
 * caller decides what to do with {@code target/tia/mode.txt}); non-zero only on a genuine error
 * (bad args, git failure, I/O failure).
 */
public final class TiaCli {

    private TiaCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("[TIA] Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Path repoRoot = Path.of(".");
        String base = null;
        String head = null;
        List<Path> classDirs = new ArrayList<>();
        Path outputDir = null;
        boolean includeUntracked = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo-root" -> repoRoot = Path.of(args[++i]);
                case "--base" -> base = args[++i];
                case "--head" -> head = args[++i];
                case "--classes-dir" -> classDirs.add(Path.of(args[++i]));
                case "--output-dir" -> outputDir = Path.of(args[++i]);
                case "--no-untracked" -> includeUntracked = false;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("--base is required, e.g. --base origin/main "
                + "(via Maven: mvn exec:java@tia -Dtia.base=origin/main)");
        }
        if (head != null && head.isBlank()) {
            head = null;
        }
        if (classDirs.isEmpty()) {
            classDirs.add(repoRoot.resolve("target/classes"));
            classDirs.add(repoRoot.resolve("target/test-classes"));
        }
        if (outputDir == null) {
            outputDir = repoRoot.resolve("target/tia");
        }

        TestImpactAnalyzer analyzer = new TestImpactAnalyzer(repoRoot, classDirs, includeUntracked);
        ImpactResult result = analyzer.analyze(base, head);
        new ReportWriter(outputDir).write(result);

        if (result.mode() == ImpactResult.Mode.FULL) {
            System.out.println("[TIA] Mode: FULL — " + result.unsafeReasons().size() + " reason(s), see "
                + outputDir.resolve("impact-report.md"));
            result.unsafeReasons().forEach(r -> System.out.println("  - " + r));
        } else {
            System.out.println("[TIA] Mode: IMPACTED — " + result.impactedTests().size() + " / "
                + result.totalTestClassesInProject() + " test classes selected. See "
                + outputDir.resolve("impact-report.md"));
        }
    }
}
