package com.automation.core.tia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Any changed file matching one of these glob patterns is treated as "can't safely reason about
 * impact" and forces a full-suite run instead of a (possibly incomplete) impacted-tests list.
 *
 * <p>Deliberately conservative: TIA saving CI minutes is worthless the moment it silently skips
 * a test that a change actually broke. Every default pattern here is a case where the dependency
 * graph and resource-literal scanning are known <b>not</b> to see the real blast radius —
 * build/tooling changes, suite topology, or config that affects every site at once. Reflection-
 * driven per-site lookups (e.g. {@code ConfigReader}'s {@code "config/" + site + ".properties"})
 * do <i>not</i> need an entry here: they're ordinary compiled classes, so any change to
 * {@code ConfigReader.java} itself is already caught by {@link DependencyGraph} the normal way
 * (every class that calls it references it in its own constant pool). What this list actually
 * covers is changes with no corresponding Java class at all.
 */
public final class UnsafeChangeRules {

    private final List<String> globs;

    private UnsafeChangeRules(List<String> globs) {
        this.globs = globs;
    }

    /**
     * Loads {@code src/test/resources/tia/unsafe-patterns.txt} relative to {@code repoRoot} if
     * present (one glob per line, {@code #} comments and blank lines ignored) so a team can tune
     * this without recompiling; otherwise falls back to {@link #defaults()}.
     */
    public static UnsafeChangeRules load(Path repoRoot) {
        Path override = repoRoot.resolve("src/test/resources/tia/unsafe-patterns.txt");
        if (Files.isRegularFile(override)) {
            List<String> lines = readPatterns(override);
            if (!lines.isEmpty()) {
                return new UnsafeChangeRules(lines);
            }
        }
        return new UnsafeChangeRules(defaults());
    }

    public static UnsafeChangeRules of(List<String> globs) {
        return new UnsafeChangeRules(new ArrayList<>(globs));
    }

    private static List<String> readPatterns(Path file) {
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    out.add(trimmed);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> defaults() {
        return List.of(
            "pom.xml",
            "checkstyle.xml",
            "owasp-suppressions.xml",
            "Dockerfile",
            "docker-compose.yml",
            "Jenkinsfile",
            ".gitlab-ci.yml",
            "testng-suites/**",
            "Scripts/**",
            ".github/workflows/**",
            // Config that applies to every site at once — a per-site config/objectrepository
            // file change is handled precisely instead, via SiteMapper's site inference.
            "src/test/resources/config/global.properties",
            "src/test/resources/config/_TEMPLATE.properties.example",
            "src/test/resources/logging.properties",
            "src/test/resources/log4j2.xml",
            "src/test/resources/allure.properties"
        );
    }

    public boolean isUnsafe(String repoRelativePath) {
        for (String glob : globs) {
            if (matches(glob, repoRelativePath)) {
                return true;
            }
        }
        return false;
    }

    public List<String> patterns() {
        return globs;
    }

    /**
     * A minimal glob matcher: {@code **} matches any depth (including zero segments),
     * {@code *} matches within one path segment (no {@code /}). Deliberately not
     * {@code FileSystems.getDefault().getPathMatcher(...)}: patterns here are matched against
     * repo-relative logical paths, not real filesystem paths, and must behave identically
     * regardless of the OS TIA runs on.
     */
    static boolean matches(String glob, String path) {
        return path.matches(globToRegex(glob));
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (".()+[]^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
