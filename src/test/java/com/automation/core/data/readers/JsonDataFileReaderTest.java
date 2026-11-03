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

class JsonDataFileReaderTest {

    private final JsonDataFileReader reader = new JsonDataFileReader();

    @Test
    void readsArrayOfObjectsInOrder(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, """
            [
              { "username": "john", "password": "pass123" },
              { "username": "jane", "password": "pass456" }
            ]
            """);

        List<DataRow> rows = reader.read(json);

        assertEquals(2, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("jane", rows.get(1).get("username"));
    }

    @Test
    void rowIndexIsOneBasedForFirstElement(@TempDir Path tempDir) throws IOException {
        // getRequired()'s error message and any 1-based "row N" reporting
        // downstream depends on this starting at 1 — a mutant flipping
        // `i + 1` to `i` in JsonDataFileReader would slip past a test that
        // only checked values, not the index.
        File json = writeJson(tempDir, """
            [ { "username": "john" } ]
            """);

        List<DataRow> rows = reader.read(json);

        assertEquals(1, rows.get(0).getRowIndex());
    }

    @Test
    void secondElementRowIndexIsTwo(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, """
            [ { "username": "john" }, { "username": "jane" } ]
            """);

        List<DataRow> rows = reader.read(json);

        assertEquals(2, rows.get(1).getRowIndex());
    }

    @Test
    void emptyArrayReturnsEmptyList(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "[]");

        List<DataRow> rows = reader.read(json);

        assertTrue(rows.isEmpty());
    }

    @Test
    void malformedJsonThrowsDataFileException(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "{ not valid json");

        assertThrows(DataFileException.class, () -> reader.read(json));
    }

    @Test
    void missingFileThrowsDataFileException(@TempDir Path tempDir) {
        File missing = tempDir.resolve("does-not-exist.json").toFile();

        assertThrows(DataFileException.class, () -> reader.read(missing));
    }

    @Test
    void numbersAndBooleansAreReadAsText(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "[{ \"age\": 30, \"active\": true }]");

        DataRow row = reader.read(json).get(0);

        assertEquals("30", row.get("age"));
        assertEquals("true", row.get("active"));
    }

    @Test
    void nullValueBecomesAnEmptyString(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "[{ \"a\": null, \"b\": \"x\" }]");

        DataRow row = reader.read(json).get(0);

        assertEquals("", row.get("a"));
        assertTrue(row.has("a"));
        assertEquals("x", row.get("b"));
    }

    @Test
    void columnNamesAreCaseInsensitiveAndValuesAreTrimmed(@TempDir Path tempDir)
        throws IOException {
        File json = writeJson(tempDir, "[{ \"UserName\": \"  john  \" }]");

        assertEquals("john", reader.read(json).get(0).get("username"));
    }

    @Test
    void nestedObjectValueIsRejected(@TempDir Path tempDir) throws IOException {
        // Rows are flat text maps; a nested object cannot become a cell value.
        File json = writeJson(tempDir, "[{ \"a\": { \"b\": 1 } }]");

        assertThrows(DataFileException.class, () -> reader.read(json));
    }

    @Test
    void topLevelObjectInsteadOfAnArrayIsRejected(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "{ \"a\": \"1\" }");

        assertThrows(DataFileException.class, () -> reader.read(json));
    }

    @Test
    void errorMessageNamesTheFile(@TempDir Path tempDir) throws IOException {
        File json = writeJson(tempDir, "{ not valid json");

        DataFileException ex = assertThrows(DataFileException.class, () -> reader.read(json));

        assertTrue(ex.getMessage().contains("data.json"), ex.getMessage());
    }

    private static File writeJson(Path tempDir, String content) throws IOException {
        Path file = tempDir.resolve("data.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
