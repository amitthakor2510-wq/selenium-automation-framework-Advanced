package com.automation.core.tia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code target/tia/coverage-map.txt} — the plain-text {@code testFqcn<TAB>coveredClassFqcn}
 * pairs {@code com.automation.core.coverage.CoverageMapBuilder} produces from a JaCoCo
 * per-test-class capture run — and answers the one question {@link TestImpactAnalyzer} actually
 * needs: "which tests were <i>observed</i>, at runtime, to execute this class". This is a second,
 * independent signal alongside {@link DependencyGraph}'s static bytecode analysis, and it's the
 * one that can see through the cases static analysis can't: a class reached only via reflection
 * with a computed (non-literal) name, dynamic dispatch, or anything else that leaves no matching
 * string anywhere in the caller's compiled constant pool. See {@code TEST_IMPACT_ANALYSIS.md} →
 * "Coverage-based fallback".
 *
 * <p>Deliberately has zero dependency on {@code org.jacoco.core} or anything else beyond the JDK
 * — same rule the rest of {@code com.automation.core.tia} follows (see e.g.
 * {@link ClassFileScanner}'s javadoc). All the JaCoCo-specific parsing happens once, offline, in
 * {@code com.automation.core.coverage.CoverageExecReader}; by the time this class reads anything,
 * it's just tab-separated text.
 *
 * <p><b>Staleness is safe by construction, not just assumed.</b> The map reflects whatever
 * commit was checked out when it was last captured — inherently older than "right now" most of
 * the time. That's fine here specifically because this data is only ever <i>added</i> to the
 * bytecode-graph result, never used to remove a test the graph would otherwise have included: a
 * stale "test T once observed class C" entry that's no longer true today only costs one extra
 * test running, and a genuinely new class that didn't exist when the map was captured simply
 * doesn't appear in it at all — the bytecode graph (which is always rebuilt from the current
 * checkout) still covers that case on its own, same as if no coverage map existed.
 */
public final class CoverageMap {

    /** An empty map — the "no coverage data available" case is just this, not a null/Optional. */
    private static final CoverageMap EMPTY = new CoverageMap(Map.of());

    private final Map<String, Set<String>> testsByClass;

    private CoverageMap(Map<String, Set<String>> testsByClass) {
        this.testsByClass = testsByClass;
    }

    /**
     * Loads {@code repoRoot/target/tia/coverage-map.txt} if it exists and is well-formed;
     * otherwise returns {@link #empty()}. Never throws for "the file just isn't there" — that's
     * the expected, common case for anyone who hasn't run a coverage-map capture yet, not an
     * error condition.
     */
    public static CoverageMap loadIfPresent(Path repoRoot) {
        Path file = repoRoot.resolve("target/tia/coverage-map.txt");
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Found " + file + " but couldn't read it", e);
        }
        Map<String, Set<String>> testsByClass = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue; // malformed line — skip rather than fail the whole load
            }
            String testFqcn = line.substring(0, tab);
            String coveredClassFqcn = line.substring(tab + 1);
            testsByClass.computeIfAbsent(coveredClassFqcn, k -> new LinkedHashSet<>()).add(testFqcn);
        }
        return new CoverageMap(testsByClass);
    }

    public static CoverageMap empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return testsByClass.isEmpty();
    }

    /** Every test class observed, during capture, to execute {@code classFqcn} — empty if none were, or if this map is empty. */
    public Set<String> testsThatObserved(String classFqcn) {
        return testsByClass.getOrDefault(classFqcn, Set.of());
    }
}
