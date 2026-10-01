package com.automation.core.tia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds every <b>concrete</b> (non-abstract) top-level class under {@code src/test/java} — the
 * candidate set a class-dependency closure gets intersected against to turn "classes affected"
 * into "TestNG classes actually worth putting in a generated suite". Deliberately does not also
 * require an {@code @Test} annotation: a class with none (a listener, a page object accidentally
 * left under {@code sites/**}) just runs zero test methods if listed, which is harmless — a
 * false negative here (silently dropping a real test class) is the outcome to avoid, not a false
 * positive.
 */
public final class TestClassDetector {

    private TestClassDetector() {
    }

    public static Set<String> findConcreteTestClasses(Path repoRoot) {
        Set<String> result = new LinkedHashSet<>();
        Path testRoot = repoRoot.resolve(SourcePathResolver.TEST_ROOT);
        if (!Files.isDirectory(testRoot)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(testRoot)) {
            for (Path javaFile : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = repoRoot.relativize(javaFile).toString().replace('\\', '/');
                SourcePathResolver.toFqcn(relative).ifPresent(fqcn -> {
                    String simpleName = SourcePathResolver.simpleNameOf(relative);
                    String content = readSafely(javaFile);
                    if (!isAbstractOrInterface(content, simpleName)) {
                        result.add(fqcn);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static String readSafely(Path javaFile) {
        try {
            return Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isAbstractOrInterface(String sourceContent, String simpleName) {
        Pattern abstractDecl = Pattern.compile(
            "abstract\\s+class\\s+" + Pattern.quote(simpleName) + "\\b");
        Pattern interfaceDecl = Pattern.compile(
            "\\binterface\\s+" + Pattern.quote(simpleName) + "\\b");
        Pattern enumDecl = Pattern.compile(
            "\\benum\\s+" + Pattern.quote(simpleName) + "\\b");
        Pattern annotationDecl = Pattern.compile(
            "@interface\\s+" + Pattern.quote(simpleName) + "\\b");
        return abstractDecl.matcher(sourceContent).find()
            || interfaceDecl.matcher(sourceContent).find()
            || enumDecl.matcher(sourceContent).find()
            || annotationDecl.matcher(sourceContent).find();
    }
}
