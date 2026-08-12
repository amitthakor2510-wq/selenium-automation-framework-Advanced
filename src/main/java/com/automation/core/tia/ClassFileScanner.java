package com.automation.core.tia;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads the CONSTANT_Utf8 constant-pool entries out of every {@code .class} file under one or
 * more output directories (e.g. {@code target/classes}, {@code target/test-classes}) and groups
 * them by <b>top-level</b> class name ({@code Outer}, not {@code Outer$1} / {@code Outer$Inner}) —
 * that's the granularity a single {@code .java} source file, and therefore a single git-diff
 * line, actually changes at.
 *
 * <p>We deliberately don't do full constant-pool structural resolution (walking CONSTANT_Class
 * to its CONSTANT_Utf8, CONSTANT_Methodref, annotation element values, generic-signature
 * attributes, etc. one relationship at a time). Instead every raw UTF8 string in the pool is
 * kept, and {@link DependencyGraph} does a substring match against known project class names.
 * This is deliberately over-inclusive: it also matches a class name that shows up only inside
 * a {@code Signature} attribute, an annotation's class-literal element (e.g.
 * {@code @Test(dataProviderClass = LoginDataProvider.class)}, whose class reference is a plain
 * {@code Lcom/automation/...;} descriptor string, not a CONSTANT_Class entry the strict form
 * would see), or a string literal built for reflection (e.g. {@code Class.forName("com.automation...")}).
 * A strict CONSTANT_Class-only reader would miss all of those — real dependencies a naive
 * import-parser or a stricter bytecode reader would silently drop, which is far worse for test
 * impact analysis than the rare over-inclusive false positive. See
 * {@code TEST_IMPACT_ANALYSIS.md} → "How dependencies are found" for the tradeoffs and the cases
 * (constructed strings, not literals) this still can't see.
 */
public final class ClassFileScanner {

    private ClassFileScanner() {
    }

    public static Map<String, Set<String>> scan(List<Path> classDirs) {
        Map<String, Set<String>> byTopLevel = new LinkedHashMap<>();
        for (Path dir : classDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
                for (Path classFile : classFiles) {
                    String relative = dir.relativize(classFile).toString().replace('\\', '/');
                    if (relative.endsWith("module-info.class") || relative.endsWith("package-info.class")) {
                        continue;
                    }
                    String fqcn = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                    String topLevel = fqcn.contains("$") ? fqcn.substring(0, fqcn.indexOf('$')) : fqcn;
                    Set<String> strings;
                    try {
                        strings = readUtf8Pool(classFile);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed reading class file: " + classFile, e);
                    }
                    byTopLevel.computeIfAbsent(topLevel, k -> new TreeSet<>()).addAll(strings);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed walking classes dir: " + dir, e);
            }
        }
        return byTopLevel;
    }

    /** Parses just enough of the {@code .class} file format (JVMS &sect;4.4) to pull out every CONSTANT_Utf8 entry. */
    static Set<String> readUtf8Pool(Path classFile) throws IOException {
        Set<String> utf8 = new TreeSet<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(classFile))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                return utf8; // not a real class file — skip quietly rather than fail the whole scan
            }
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int poolCount = in.readUnsignedShort();
            // Constant pool entries are 1-indexed; CONSTANT_Long/CONSTANT_Double occupy two slots.
            for (int i = 1; i < poolCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8.add(in.readUTF()); // CONSTANT_Utf8: length-prefixed modified-UTF8, same as readUTF()
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2); // Class, String, MethodType, Module, Package
                    case 15 -> in.skipBytes(3); // MethodHandle
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4); // Integer, Float, *ref, NameAndType, Dynamic, InvokeDynamic
                    case 5, 6 -> { // Long, Double
                        in.skipBytes(8);
                        i++; // occupies two constant-pool indices
                    }
                    default -> throw new IOException("Unknown constant pool tag " + tag + " in " + classFile
                        + " at index " + i + " — class file format newer than this reader understands.");
                }
            }
        }
        return utf8;
    }
}
