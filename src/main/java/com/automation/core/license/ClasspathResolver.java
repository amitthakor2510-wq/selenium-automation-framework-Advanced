package com.automation.core.license;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Works out which dependency jars to scan. Order of preference:
 * <ol>
 *   <li>{@code -Dlicense.classpath.file=<file>}: a file holding a classpath (one entry per line, or
 *       joined with the OS path separator - exactly what {@code mvn dependency:build-classpath
 *       -Dmdep.outputFile=<file>} writes);</li>
 *   <li>the running JVM: URLs of the context class-loader chain (what {@code exec:java} uses) plus
 *       {@code java.class.path}, with a jar's manifest {@code Class-Path} expanded (what Surefire's
 *       booter jar uses to list the whole test classpath).</li>
 * </ol>
 * Only existing {@code .jar} files are returned; class directories (the project's own code) are skipped.
 */
public final class ClasspathResolver {

    private ClasspathResolver() {
    }

    public static List<Path> resolve() {
        String file = System.getProperty("license.classpath.file");
        if (file != null && !file.isBlank()) {
            return fromFile(Path.of(file.trim()));
        }
        return fromRuntime();
    }

    static List<Path> fromFile(Path file) {
        Set<Path> out = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(file)) {
                for (String entry : line.split(java.io.File.pathSeparator)) {
                    addIfJar(out, entry.trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read license.classpath.file " + file + ": " + e.getMessage(), e);
        }
        return new ArrayList<>(out);
    }

    private static List<Path> fromRuntime() {
        Set<Path> entries = new LinkedHashSet<>();
        for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    addUrl(entries, url);
                }
            }
        }
        // java.class.path is only the right source when no URL class loader described the project (Surefire).
        // Under exec:java it is Maven's own boot jar, which must not be scanned as if it were a dependency.
        if (entries.isEmpty()) {
            for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
                addIfJar(entries, entry.trim());
            }
        }
        // Expand manifest Class-Path once (not recursively) - the Surefire booter jar lists every dependency there.
        Set<Path> expanded = new LinkedHashSet<>(entries);
        for (Path jar : entries) {
            expanded.addAll(manifestClassPath(jar));
        }
        return new ArrayList<>(expanded);
    }

    private static void addUrl(Set<Path> out, URL url) {
        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                addIfJar(out, Path.of(url.toURI()).toString());
            }
        } catch (Exception e) {
            // A URL that is not a plain file path is simply not a scannable jar.
        }
    }

    private static void addIfJar(Set<Path> out, String entry) {
        if (entry.isEmpty() || !entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return;
        }
        Path p = Path.of(entry).toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) {
            out.add(p);
        }
    }

    private static List<Path> manifestClassPath(Path jar) {
        List<Path> out = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            String cp = mf == null ? null : mf.getMainAttributes().getValue("Class-Path");
            if (cp == null) {
                return out;
            }
            URI base = jar.toUri();
            for (String token : cp.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                URI resolved = base.resolve(token);
                if ("file".equalsIgnoreCase(resolved.getScheme())) {
                    Set<Path> single = new LinkedHashSet<>();
                    addIfJar(single, Path.of(resolved).toString());
                    out.addAll(single);
                }
            }
        } catch (Exception e) {
            // Unreadable jar or malformed Class-Path entry: nothing to expand from this one.
        }
        return out;
    }
}
