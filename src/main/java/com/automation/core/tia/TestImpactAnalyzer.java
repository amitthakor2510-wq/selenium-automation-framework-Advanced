package com.automation.core.tia;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates test-impact analysis end to end: git diff -&gt; classify each changed file -&gt;
 * either bail out to "run everything" (unsafe change) or walk the compiled-class dependency
 * graph plus the resource-literal/site fallbacks to produce the precise set of impacted test
 * classes. See {@code TEST_IMPACT_ANALYSIS.md} at the repo root for the full design writeup,
 * the CLI, and the CI wiring.
 */
public final class TestImpactAnalyzer {

    private final Path repoRoot;
    private final List<Path> classDirs;
    private final boolean includeUntracked;

    public TestImpactAnalyzer(Path repoRoot, List<Path> classDirs, boolean includeUntracked) {
        this.repoRoot = repoRoot;
        this.classDirs = classDirs;
        this.includeUntracked = includeUntracked;
    }

    public ImpactResult analyze(String base, String head) throws IOException, InterruptedException {
        GitDiffReader gitDiffReader = new GitDiffReader(repoRoot);
        List<ChangedFile> changed = new ArrayList<>(gitDiffReader.diff(base, head));
        if (includeUntracked) {
            Set<String> alreadySeen = new LinkedHashSet<>();
            for (ChangedFile f : changed) {
                alreadySeen.add(f.path());
            }
            for (ChangedFile f : gitDiffReader.untracked()) {
                if (alreadySeen.add(f.path())) {
                    changed.add(f);
                }
            }
        }
        return analyze(changed);
    }

    /** Overload for callers (and tests) that already have the changed-file list, e.g. from a pre-computed diff. */
    public ImpactResult analyze(List<ChangedFile> changed) {
        UnsafeChangeRules unsafeRules = UnsafeChangeRules.load(repoRoot);
        List<String> unsafeReasons = new ArrayList<>();

        List<ChangedFile> mainChanges = new ArrayList<>();
        List<ChangedFile> testChanges = new ArrayList<>();
        List<ChangedFile> resourceChanges = new ArrayList<>();

        for (ChangedFile f : changed) {
            if (unsafeRules.isUnsafe(f.path())) {
                unsafeReasons.add("Build/config/infra file changed: " + f.path()
                    + " — its effect on tests can't be determined from source/bytecode alone.");
                continue;
            }
            SourcePathResolver.Root root = SourcePathResolver.rootOf(f.path());
            if (root == SourcePathResolver.Root.MAIN && f.path().endsWith(".java")) {
                if (f.type() == ChangeType.DELETED) {
                    unsafeReasons.add("Main source file deleted: " + f.path()
                        + " — can't safely determine what depended on it.");
                } else {
                    mainChanges.add(f);
                }
            } else if (root == SourcePathResolver.Root.TEST && f.path().endsWith(".java")) {
                if (f.type() != ChangeType.DELETED) {
                    testChanges.add(f);
                }
                // A deleted test file needs no action — there's nothing left to run.
            } else if (f.path().startsWith("src/test/resources/") || f.path().startsWith("src/main/resources/")) {
                resourceChanges.add(f);
            }
            // Anything else (docs, IDE metadata, README, LICENSE, .gitignore, .idea/**, etc.)
            // has no test-execution impact and is silently ignored.
        }

        if (!unsafeReasons.isEmpty()) {
            return new ImpactResult(ImpactResult.Mode.FULL, unsafeReasons, Map.of(), -1, changed);
        }

        Map<String, Set<String>> utf8ByClass = ClassFileScanner.scan(classDirs);
        DependencyGraph graph = DependencyGraph.build(utf8ByClass);
        Set<String> concreteTestClasses = TestClassDetector.findConcreteTestClasses(repoRoot);
        ResourceReferenceIndex resourceIndex = ResourceReferenceIndex.build(repoRoot);

        Map<String, List<ImpactResult.ImpactReasonDetail>> impacted = new LinkedHashMap<>();

        // 1) Test-source changes: always include the changed class itself directly, whether or
        //    not it's in the compiled graph yet (a brand-new file won't be compiled on the first
        //    pass — that's NEW_TEST_FILE, handled without the graph). If it IS already compiled,
        //    it also seeds the reverse closure below — a changed shared test-source class (a
        //    BaseTest superclass, a @DataProvider holder, a keyword-engine test base) can affect
        //    every test that depends on it exactly the way a changed main class can, and must be
        //    walked the same way, not just recorded as "changed" on its own.
        Set<String> testSeeds = new LinkedHashSet<>();
        for (ChangedFile f : testChanges) {
            SourcePathResolver.toFqcn(f.path()).ifPresent(fqcn -> {
                // Only record it as an "impacted test to run" if it's actually runnable — an
                // abstract shared base class (BaseTest, KeywordTestBase, MobileBaseTest) has no
                // @Test methods of its own and shouldn't inflate the impacted count or show up in
                // a generated suite. It still needs to seed the closure below so its concrete
                // subclasses are found, which happens independently of this check.
                if (concreteTestClasses.contains(fqcn)) {
                    ImpactReason reason = f.type() == ChangeType.ADDED ? ImpactReason.NEW_TEST_FILE : ImpactReason.SELF_CHANGED;
                    addReason(impacted, fqcn, reason, f.path());
                }
                if (graph.knownClasses().contains(fqcn)) {
                    testSeeds.add(fqcn);
                }
            });
        }

        // 2) Main-source changes: seed the graph the same way.
        Set<String> mainSeeds = new LinkedHashSet<>();
        for (ChangedFile f : mainChanges) {
            SourcePathResolver.toFqcn(f.path()).ifPresentOrElse(fqcn -> {
                if (graph.knownClasses().contains(fqcn)) {
                    mainSeeds.add(fqcn);
                } else {
                    unsafeReasons.add("Changed main class not found in compiled output: " + fqcn
                        + " (path " + f.path() + ") — run `mvn compile test-compile` first. Falling back to full suite.");
                }
            }, () -> unsafeReasons.add("Could not resolve a class name for changed main file: " + f.path()));
        }
        if (!unsafeReasons.isEmpty()) {
            return new ImpactResult(ImpactResult.Mode.FULL, unsafeReasons, Map.of(), -1, changed);
        }

        // 3) Walk the reverse-transitive closure from every seed (main + test) together, so a
        //    change to a shared test-source base class fans out to its subclasses exactly like a
        //    changed main-source utility class fans out to its callers.
        Set<String> allSeeds = new LinkedHashSet<>();
        allSeeds.addAll(mainSeeds);
        allSeeds.addAll(testSeeds);
        if (!allSeeds.isEmpty()) {
            Set<String> closure = graph.reverseTransitiveClosure(allSeeds);
            for (String fqcn : closure) {
                if (!concreteTestClasses.contains(fqcn)) {
                    continue;
                }
                // The seed classes themselves already have a more specific SELF_CHANGED /
                // NEW_TEST_FILE reason recorded above — don't also claim they "depend on" themselves.
                if (allSeeds.contains(fqcn)) {
                    continue;
                }
                addReason(impacted, fqcn, ImpactReason.DEPENDS_ON_CHANGED_CLASS,
                    "depends on changed class(es): " + String.join(", ", allSeeds));
            }
        }

        // 3) Resource changes: literal reference first, then per-site fallback, then unsafe.
        for (ChangedFile f : resourceChanges) {
            Set<String> literalRefs = resourceIndex.referencingClasses(f.path());
            if (!literalRefs.isEmpty()) {
                for (String fqcn : literalRefs) {
                    if (concreteTestClasses.contains(fqcn)) {
                        addReason(impacted, fqcn, ImpactReason.RESOURCE_LITERAL_REFERENCE, f.path());
                    }
                }
                continue;
            }
            var site = SiteMapper.siteFromResourcePath(f.path());
            if (site.isPresent()) {
                for (String fqcn : concreteTestClasses) {
                    if (SiteMapper.belongsToSite(fqcn, site.get())) {
                        addReason(impacted, fqcn, ImpactReason.RESOURCE_SITE_FALLBACK,
                            f.path() + " (site=" + site.get() + ", no literal reference found)");
                    }
                }
                continue;
            }
            unsafeReasons.add("Resource file changed with no literal reference and no inferable site: "
                + f.path() + " — falling back to full suite.");
        }

        if (!unsafeReasons.isEmpty()) {
            return new ImpactResult(ImpactResult.Mode.FULL, unsafeReasons, Map.of(), -1, changed);
        }

        return new ImpactResult(ImpactResult.Mode.IMPACTED, List.of(), impacted, concreteTestClasses.size(), changed);
    }

    private static void addReason(Map<String, List<ImpactResult.ImpactReasonDetail>> impacted,
                                   String fqcn, ImpactReason reason, String detail) {
        impacted.computeIfAbsent(fqcn, k -> new ArrayList<>())
            .add(new ImpactResult.ImpactReasonDetail(reason, detail));
    }
}
