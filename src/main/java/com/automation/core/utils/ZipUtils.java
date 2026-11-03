package com.automation.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Small, dependency-free zip helper (JDK {@code java.util.zip} only), mainly for packaging a
 * finished report folder into a single file that can be attached to an email, uploaded to a chat
 * or kept as a CI artifact.
 *
 * <ul>
 *   <li>{@link #zipDirectory(Path, Path)} - whole folder tree, empty folders included.</li>
 *   <li>{@link #zipFiles(Collection, Path)} - an explicit list of files, stored flat by file name.</li>
 *   <li>{@link #unzip(Path, Path)} - extract, with Zip-Slip protection.</li>
 * </ul>
 *
 * <p>Behaviour worth knowing:
 * <ul>
 *   <li>Entry names always use {@code /}, whatever the OS - a zip written on Windows opens
 *       correctly everywhere.</li>
 *   <li>If the target zip lives INSIDE the folder being zipped (a common slip, e.g. zipping
 *       {@code target/reports} to {@code target/reports/all.zip}) it is skipped instead of being
 *       swallowed into itself.</li>
 *   <li>The zip is written to a uniquely named temporary sibling and moved into place only on
 *       success, so a failure never leaves a truncated archive that looks valid and concurrent
 *       writers never share a temp file.</li>
 *   <li>Symbolic links are not followed.</li>
 * </ul>
 */
public final class ZipUtils {

    private static final Logger logger = LoggerFactory.getLogger(ZipUtils.class);

    /** {@link #unzip(Path, Path)} refuses an archive that expands beyond this many bytes in total (zip bomb). */
    static final long MAX_UNZIP_BYTES = 2L * 1024 * 1024 * 1024;

    /** {@link #unzip(Path, Path)} refuses an archive with more entries than this. */
    static final int MAX_UNZIP_ENTRIES = 100_000;

    private ZipUtils() {
    }

    /** Zips the CONTENTS of {@code sourceDir} (no wrapping top-level folder) into {@code zipFile}. */
    public static Path zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        return zipDirectory(sourceDir, zipFile, false);
    }

    /**
     * Zips {@code sourceDir} into {@code zipFile}.
     *
     * @param includeRootDir true to put everything under a top-level folder named after
     *                       {@code sourceDir}; false to store its contents at the zip root
     * @return {@code zipFile}
     */
    public static Path zipDirectory(Path sourceDir, Path zipFile, boolean includeRootDir) throws IOException {
        if (sourceDir == null || zipFile == null) {
            throw new IllegalArgumentException("sourceDir and zipFile must not be null");
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Not a directory, nothing to zip: " + sourceDir);
        }
        Path root = sourceDir.toAbsolutePath().normalize();
        Path target = zipFile.toAbsolutePath().normalize();
        String prefix = includeRootDir && root.getFileName() != null ? root.getFileName() + "/" : "";

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root)).filter(p -> !p.equals(target)).sorted().forEach(entries::add);
        }

        writeAtomically(target, zip -> {
            int files = 0;
            if (!prefix.isEmpty()) {
                putDirectoryEntry(zip, prefix);
            }
            for (Path p : entries) {
                String name = prefix + toEntryName(root.relativize(p));
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    putDirectoryEntry(zip, name + "/");
                } else if (Files.isRegularFile(p)) {
                    putFileEntry(zip, p, name);
                    files++;
                }
            }
            logger.info("[ZipUtils] Zipped {} file(s) from {} into {}", files, root, target);
        });
        return zipFile;
    }

    /** Zips {@code files} into {@code zipFile}, each stored at the root under its own file name. */
    public static Path zipFiles(Collection<Path> files, Path zipFile) throws IOException {
        if (files == null || files.isEmpty() || zipFile == null) {
            throw new IllegalArgumentException("files must not be empty and zipFile must not be null");
        }
        Set<String> names = new HashSet<>();
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                throw new IOException("Not a regular file, cannot zip: " + f);
            }
            if (!names.add(f.getFileName().toString())) {
                throw new IllegalArgumentException("Two files share the name '" + f.getFileName()
                    + "' - they would collide in the flat zip");
            }
        }
        Path target = zipFile.toAbsolutePath().normalize();
        writeAtomically(target, zip -> {
            for (Path f : files) {
                if (f.toAbsolutePath().normalize().equals(target)) {
                    continue;
                }
                putFileEntry(zip, f, f.getFileName().toString());
            }
            logger.info("[ZipUtils] Zipped {} file(s) into {}", files.size(), target);
        });
        return zipFile;
    }

    /**
     * Extracts {@code zipFile} into {@code destDir} (created if missing) and returns
     * {@code destDir}. An entry that would land outside {@code destDir} (e.g. {@code ../evil}) aborts the
     * extraction with an {@link IOException}, as does an archive that expands past {@link #MAX_UNZIP_BYTES}
     * in total or holds more than {@link #MAX_UNZIP_ENTRIES} entries (zip bomb).
     */
    public static Path unzip(Path zipFile, Path destDir) throws IOException {
        return unzip(zipFile, destDir, MAX_UNZIP_BYTES, MAX_UNZIP_ENTRIES);
    }

    /** As {@link #unzip(Path, Path)} with explicit limits on the total extracted size and the entry count. */
    static Path unzip(Path zipFile, Path destDir, long maxTotalBytes, int maxEntries) throws IOException {
        if (zipFile == null || destDir == null) {
            throw new IllegalArgumentException("zipFile and destDir must not be null");
        }
        Path dest = destDir.toAbsolutePath().normalize();
        Files.createDirectories(dest);
        long total = 0;
        int entries = 0;
        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw new IOException("Blocked zip with more than " + maxEntries + " entries: " + zipFile);
                }
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("Blocked zip entry outside the target folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) {
                        Files.createDirectories(out.getParent());
                    }
                    total += copyBounded(zip, out, maxTotalBytes - total, entry.getName());
                }
                zip.closeEntry();
            }
        }
        return destDir;
    }

    /**
     * Copies the current zip entry to {@code out}, failing as soon as more than {@code remaining} bytes
     * have been produced. An existing file is replaced (deleted first, so a symlink is never written through).
     */
    private static long copyBounded(InputStream in, Path out, long remaining, String entryName) throws IOException {
        Files.deleteIfExists(out);
        long written = 0;
        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                written += n;
                if (written > remaining) {
                    throw new IOException("Blocked zip: extracting '" + entryName
                        + "' would exceed the total size limit (zip bomb?)");
                }
                os.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(out);
            throw e;
        }
        return written;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ZipWriter {
        void write(ZipOutputStream zip) throws IOException;
    }

    private static void writeAtomically(Path target, ZipWriter writer) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        // Unique per call, so two threads writing the same target never share a temp file.
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                writer.write(zip);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void putDirectoryEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
    }

    private static void putFileEntry(ZipOutputStream zip, Path file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static String toEntryName(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
