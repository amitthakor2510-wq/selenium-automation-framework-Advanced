package com.automation.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-filesystem unit tests for {@link ZipUtils} (JUnit 5, run via {@code -Punit-tests}). */
class ZipUtilsTest {

    @TempDir
    Path tmp;

    private Path sampleTree() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("report"));
        Files.writeString(src.resolve("index.html"), "<html>hi</html>");
        Files.createDirectories(src.resolve("data/nested"));
        Files.writeString(src.resolve("data/nested/results.json"), "{\"ok\":true}");
        Files.writeString(src.resolve("data/unicode-content.txt"), "unicode नाम", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("empty"));
        return src;
    }

    private static List<String> entryNames(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().map(ZipEntry::getName).forEach(names::add);
        }
        return names;
    }

    @Test
    void zipsTheContentsAtTheZipRootWithForwardSlashesAndEmptyFolders() throws IOException {
        Path zip = ZipUtils.zipDirectory(sampleTree(), tmp.resolve("out/report.zip"));
        List<String> names = entryNames(zip);
        assertTrue(names.contains("index.html"), names.toString());
        assertTrue(names.contains("data/nested/results.json"), names.toString());
        assertTrue(names.contains("data/unicode-content.txt"), names.toString());
        assertTrue(names.contains("empty/"), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.contains("\\")), "backslash in entry name: " + names);
    }

    @Test
    void includeRootDirWrapsEverythingInATopLevelFolder() throws IOException {
        Path zip = ZipUtils.zipDirectory(sampleTree(), tmp.resolve("wrapped.zip"), true);
        List<String> names = entryNames(zip);
        assertTrue(names.contains("report/index.html"), names.toString());
        assertTrue(names.stream().allMatch(n -> n.startsWith("report/")), names.toString());
    }

    @Test
    void roundTripRestoresTheSameFilesAndContent() throws IOException {
        Path zip = ZipUtils.zipDirectory(sampleTree(), tmp.resolve("rt.zip"));
        Path out = ZipUtils.unzip(zip, tmp.resolve("restored"));
        assertEquals("<html>hi</html>", Files.readString(out.resolve("index.html")));
        assertEquals("{\"ok\":true}", Files.readString(out.resolve("data/nested/results.json")));
        assertEquals("unicode नाम", Files.readString(out.resolve("data/unicode-content.txt"), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(out.resolve("empty")));
    }

    @Test
    void aZipWrittenInsideTheSourceFolderDoesNotSwallowItself() throws IOException {
        Path src = sampleTree();
        Path zip = ZipUtils.zipDirectory(src, src.resolve("all.zip"));
        assertFalse(entryNames(zip).contains("all.zip"), entryNames(zip).toString());
        assertFalse(entryNames(zip).stream().anyMatch(n -> n.endsWith(".tmp")), entryNames(zip).toString());
    }

    @Test
    void rewritingAnExistingZipReplacesItAndLeavesNoTempFile() throws IOException {
        Path src = sampleTree();
        Path zip = tmp.resolve("again.zip");
        ZipUtils.zipDirectory(src, zip);
        Files.writeString(src.resolve("later.txt"), "added after the first zip");
        ZipUtils.zipDirectory(src, zip);
        assertTrue(entryNames(zip).contains("later.txt"));
        try (java.util.stream.Stream<Path> left = Files.list(tmp)) {
            assertFalse(left.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")), "temp file left behind");
        }
    }

    @Test
    void zipDirectoryRejectsAMissingOrNonDirectorySource() throws IOException {
        assertThrows(IOException.class, () -> ZipUtils.zipDirectory(tmp.resolve("nope"), tmp.resolve("x.zip")));
        Path file = Files.writeString(tmp.resolve("plain.txt"), "x");
        assertThrows(IOException.class, () -> ZipUtils.zipDirectory(file, tmp.resolve("x.zip")));
        assertFalse(Files.exists(tmp.resolve("x.zip")));
    }

    @Test
    void zipFilesStoresEachFileFlatByName() throws IOException {
        Path a = Files.writeString(tmp.resolve("a.txt"), "A");
        Files.createDirectories(tmp.resolve("sub"));
        Path b = Files.writeString(tmp.resolve("sub/b.txt"), "B");
        Path zip = ZipUtils.zipFiles(List.of(a, b), tmp.resolve("flat.zip"));
        assertEquals(List.of("a.txt", "b.txt"), entryNames(zip));
    }

    @Test
    void zipFilesRejectsDuplicateNamesAndNonFiles() throws IOException {
        Path a = Files.writeString(tmp.resolve("same.txt"), "1");
        Files.createDirectories(tmp.resolve("other"));
        Path b = Files.writeString(tmp.resolve("other/same.txt"), "2");
        assertThrows(IllegalArgumentException.class, () -> ZipUtils.zipFiles(List.of(a, b), tmp.resolve("d.zip")));
        assertThrows(IOException.class, () -> ZipUtils.zipFiles(List.of(tmp.resolve("other")), tmp.resolve("d.zip")));
        assertThrows(IllegalArgumentException.class, () -> ZipUtils.zipFiles(List.of(), tmp.resolve("d.zip")));
    }

    @Test
    void unzipBlocksEntriesThatEscapeTheTargetFolder() throws IOException {
        Path evil = tmp.resolve("evil.zip");
        try (OutputStream out = Files.newOutputStream(evil); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("pwned".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path dest = tmp.resolve("safe");
        assertThrows(IOException.class, () -> ZipUtils.unzip(evil, dest));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
    }
}
