package com.automation.core.tia;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for TIA's own tests: writing a small {@code .java} source tree under a temp
 * dir and compiling it with the in-JVM {@link JavaCompiler}, so these tests exercise the real
 * {@code javac}-produced {@code .class} file format {@link ClassFileScanner} parses — no mocking
 * of bytecode, no external {@code javac} process dependency.
 */
final class TiaTestFixtures {

    private TiaTestFixtures() {
    }

    /** Writes {@code content} to {@code repoRoot/relativePath}, creating parent directories as needed. */
    static Path write(Path repoRoot, String relativePath, String content) {
        try {
            Path file = repoRoot.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles every {@code .java} file under {@code sourceRoot} into {@code outputDir}. */
    static void compile(Path sourceRoot, Path outputDir) {
        compile(sourceRoot, outputDir, List.of());
    }

    /**
     * Compiles every {@code .java} file under {@code sourceRoot} into {@code outputDir}, with
     * {@code extraClasspath} appended after {@code outputDir} on the compiler's {@code -cp}.
     * Needed when {@code sourceRoot} is a test-source tree whose fixtures import classes from an
     * already-compiled main-source tree (e.g. {@code compileAll()} compiling {@code
     * src/test/java} after {@code src/main/java} in {@link TestImpactAnalyzerIntegrationTest}) —
     * without this, javac can't resolve those imports even though the referenced {@code .class}
     * files already exist on disk, because {@code outputDir} alone only puts the test tree's own
     * output on the classpath.
     */
    static void compile(Path sourceRoot, Path outputDir, List<Path> extraClasspath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available — tests must run on a JDK, not a JRE.");
        }
        try {
            Files.createDirectories(outputDir);
            List<String> sources = new ArrayList<>();
            try (var walk = Files.walk(sourceRoot)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
            }
            List<String> classpath = new ArrayList<>();
            classpath.add(outputDir.toString());
            for (Path extra : extraClasspath) {
                classpath.add(extra.toString());
            }
            List<String> args = new ArrayList<>();
            args.add("-d");
            args.add(outputDir.toString());
            args.add("-cp");
            args.add(String.join(java.io.File.pathSeparator, classpath));
            args.addAll(sources);
            int result = compiler.run(null, null, null, args.toArray(new String[0]));
            if (result != 0) {
                throw new IllegalStateException("javac failed compiling " + sourceRoot + " -> " + outputDir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
