package com.automation.core.tia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Many resource files (test data under {@code testdata/**}, keyword scripts) are opened by a
 * literal string path in test source — e.g.
 * {@code DataProviderFactory.fromFile("src/test/resources/testdata/login.yaml")} — rather than
 * through any Java-level reference {@link DependencyGraph} could see (a resource file has no
 * bytecode of its own). This scans every {@code .java} source file once and records, per
 * resource basename, which top-level classes mention it literally, so a resource-only change
 * can still be traced to the specific tests that read it instead of falling back to a full-suite
 * run every time test data changes.
 *
 * <p>This intentionally does not try to resolve paths built by concatenation (e.g.
 * {@code ConfigReader}'s {@code "config/" + site + ".properties"}, or
 * {@code ObjectRepository}'s {@code "objectrepository/" + site + ".properties"}) — those aren't
 * literals to find. {@link SiteMapper}'s site-inference fallback covers that case instead; see
 * {@code TEST_IMPACT_ANALYSIS.md}.
 */
public final class ResourceReferenceIndex {

    private static final Pattern RESOURCE_LITERAL =
        Pattern.compile("\"([^\"]*\\.(?:csv|json|ya?ml|xlsx|zip|properties))\"");

    private final Map<String, Set<String>> classesByBasename = new LinkedHashMap<>();

    public static ResourceReferenceIndex build(Path repoRoot) {
        ResourceReferenceIndex index = new ResourceReferenceIndex();
        index.scanRoot(repoRoot, repoRoot.resolve(SourcePathResolver.TEST_ROOT));
        index.scanRoot(repoRoot, repoRoot.resolve(SourcePathResolver.MAIN_ROOT));
        return index;
    }

    private void scanRoot(Path repoRoot, Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path javaFile : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = repoRoot.relativize(javaFile).toString().replace('\\', '/');
                SourcePathResolver.toFqcn(relative).ifPresent(fqcn -> recordMentions(fqcn, readSafely(javaFile)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readSafely(Path javaFile) {
        try {
            return Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void recordMentions(String fqcn, String sourceContent) {
        Matcher m = RESOURCE_LITERAL.matcher(sourceContent);
        while (m.find()) {
            String literal = m.group(1);
            String basename = literal.substring(literal.lastIndexOf('/') + 1);
            classesByBasename.computeIfAbsent(basename, k -> new LinkedHashSet<>()).add(fqcn);
        }
    }

    /**
     * Top-level classes (test or main) whose source literally mentions this resource file's
     * basename. Empty if no source file references it by a literal path — the caller should
     * fall back to a coarser rule (see {@link SiteMapper}) rather than assume "no impact".
     */
    public Set<String> referencingClasses(String resourceRepoRelativePath) {
        String basename = resourceRepoRelativePath.substring(resourceRepoRelativePath.lastIndexOf('/') + 1);
        return classesByBasename.getOrDefault(basename, Set.of());
    }
}
