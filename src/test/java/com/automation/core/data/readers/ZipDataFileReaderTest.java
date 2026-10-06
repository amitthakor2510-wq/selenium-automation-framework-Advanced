package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real zip files on disk, read back through the real registry. Guards the macOS case: Finder/zip
 * add "__MACOSX/._login.csv" shadow entries that end in ".csv" but are binary metadata.
 */
class ZipDataFileReaderTest {

    private final DataFileReaderRegistry registry = new DataFileReaderRegistry();

    @Test
    void macOsMetadataEntriesAreIgnored(@TempDir Path tempDir) throws IOException {
        File zip = tempDir.resolve("data.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            put(out, "login.csv", "user,pass\nalice,pw1\nbob,pw2\n");
            put(out, "__MACOSX/._login.csv", "\u0000\u0005\u0016\u0007 not really csv");
            put(out, "nested/._other.csv", "\u0000\u0005\u0016\u0007 not really csv");
        }

        List<DataRow> rows = registry.readAll(zip);

        assertEquals(2, rows.size());
        assertEquals("alice", rows.get(0).getRequired("user"));
        assertEquals("bob", rows.get(1).getRequired("user"));
    }

    @Test
    void rowIndicesAreUniqueAcrossFilesInTheZip(@TempDir Path tempDir) throws IOException {
        File zip = tempDir.resolve("data.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            put(out, "a.csv", "user\none\ntwo\n");
            put(out, "b.csv", "user\nthree\n");
        }

        List<DataRow> rows = registry.readAll(zip);

        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).getRowIndex());
        assertEquals(2, rows.get(1).getRowIndex());
        assertEquals(3, rows.get(2).getRowIndex());
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
