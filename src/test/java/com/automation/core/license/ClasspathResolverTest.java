package com.automation.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClasspathResolverTest {

    private static Path emptyJar(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jos = new JarOutputStream(out)) {
            jos.flush();
        }
        return file;
    }

    @Test
    void fileWithPathSeparatorOrOnePerLine(@TempDir Path dir) throws IOException {
        Path a = emptyJar(dir.resolve("a.jar"));
        Path b = emptyJar(dir.resolve("b.jar"));
        Path classes = Files.createDirectory(dir.resolve("classes"));
        Path file = dir.resolve("cp.txt");
        Files.writeString(file, a + File.pathSeparator + classes + File.pathSeparator + dir.resolve("missing.jar")
            + "\n" + b + "\n" + a + "\n");

        List<Path> jars = ClasspathResolver.fromFile(file);
        assertEquals(List.of(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize()), jars,
            "directories, missing files and duplicates are dropped; order is kept");
    }

    @Test
    void unreadableFileIsAClearError(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ClasspathResolver.fromFile(dir.resolve("nope.txt")));
        assertEquals(true, e.getMessage().contains("license.classpath.file"));
    }
}
