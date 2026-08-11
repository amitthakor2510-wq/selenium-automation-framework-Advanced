package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No WebDriver involved — just real temp files on disk read back through
 * the actual reader, so a mutant that flips a loop bound, an off-by-one on
 * the header/data row split, or the ragged-row padding logic gets caught
 * by an assertion on actual output, not just "did it throw."
 */
class CsvDataFileReaderTest {

    private final CsvDataFileReader reader = new CsvDataFileReader();

    @Test
    void readsHeaderAndDataRowsInOrder(@TempDir Path tempDir) throws IOException {
        File csv = writeCsv(tempDir, "username,password\njohn,pass123\njane,pass456\n");

        List<DataRow> rows = reader.read(csv);

        assertEquals(2, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("pass123", rows.get(0).get("password"));
        assertEquals("jane", rows.get(1).get("username"));
        assertEquals("pass456", rows.get(1).get("password"));
    }

    @Test
    void skipsFullyBlankRows(@TempDir Path tempDir) throws IOException {
        // Middle row is blank (both fields empty) — must be skipped, not
        // turned into a DataRow with two empty-string values.
        File csv = writeCsv(tempDir, "username,password\njohn,pass123\n,\njane,pass456\n");

        List<DataRow> rows = reader.read(csv);

        assertEquals(2, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("jane", rows.get(1).get("username"));
    }

    @Test
    void raggedRowShorterThanHeaderIsPaddedWithEmptyString(@TempDir Path tempDir) throws IOException {
        // Second column has no value at all on this data row (not even a
        // trailing comma) — reader must not throw ArrayIndexOutOfBounds,
        // and the missing cell must read back as "".
        File csv = writeCsv(tempDir, "username,password,expected\njohn\n");

        List<DataRow> rows = reader.read(csv);

        assertEquals(1, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("", rows.get(0).get("password"));
        assertEquals("", rows.get(0).get("expected"));
    }

    @Test
    void emptyFileWithNoRowsAtAllReturnsEmptyList(@TempDir Path tempDir) throws IOException {
        File csv = writeCsv(tempDir, "");

        List<DataRow> rows = reader.read(csv);

        assertTrue(rows.isEmpty());
    }

    @Test
    void fileWithOnlyAHeaderRowReturnsEmptyList(@TempDir Path tempDir) throws IOException {
        File csv = writeCsv(tempDir, "username,password\n");

        List<DataRow> rows = reader.read(csv);

        assertTrue(rows.isEmpty());
    }

    @Test
    void nonAsciiContentIsReadCorrectlyAsUtf8(@TempDir Path tempDir) throws IOException {
        File csv = writeCsv(tempDir, "name,city\nJos\u00e9,S\u00e3o Paulo\n");

        List<DataRow> rows = reader.read(csv);

        assertEquals("Jos\u00e9", rows.get(0).get("name"));
        assertEquals("S\u00e3o Paulo", rows.get(0).get("city"));
    }

    @Test
    void missingFileThrowsDataFileExceptionNotAnUncheckedIoException(@TempDir Path tempDir) {
        File missing = tempDir.resolve("does-not-exist.csv").toFile();

        assertThrows(DataFileException.class, () -> reader.read(missing));
    }

    private static File writeCsv(Path tempDir, String content) throws IOException {
        Path file = tempDir.resolve("data.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
