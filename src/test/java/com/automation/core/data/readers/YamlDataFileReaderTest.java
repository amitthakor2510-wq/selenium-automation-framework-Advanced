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

/** Real temp files read back through the actual reader; no browser involved. */
class YamlDataFileReaderTest {

    private final YamlDataFileReader reader = new YamlDataFileReader();

    @Test
    void readsAListOfMappingsInOrderWithOneBasedRowIndexes(@TempDir Path tempDir)
        throws IOException {
        File yaml = writeYaml(tempDir, "- username: john\n  password: pass123\n"
            + "- username: jane\n  password: pass456\n");

        List<DataRow> rows = reader.read(yaml);

        assertEquals(2, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("pass123", rows.get(0).get("password"));
        assertEquals(1, rows.get(0).getRowIndex());
        assertEquals("jane", rows.get(1).get("username"));
        assertEquals(2, rows.get(1).getRowIndex());
    }

    @Test
    void numbersAndBooleansAreReadAsText(@TempDir Path tempDir) throws IOException {
        File yaml = writeYaml(tempDir, "- age: 30\n  active: true\n");

        DataRow row = reader.read(yaml).get(0);

        assertEquals("30", row.get("age"));
        assertEquals("true", row.get("active"));
    }

    @Test
    void emptyValueBecomesAnEmptyString(@TempDir Path tempDir) throws IOException {
        File yaml = writeYaml(tempDir, "- a:\n  b: x\n");

        DataRow row = reader.read(yaml).get(0);

        assertTrue(row.has("a"));
        assertEquals("", row.get("a"));
        assertEquals("x", row.get("b"));
    }

    @Test
    void entriesThatAreNotMappingsAreSkippedButKeepTheirPosition(@TempDir Path tempDir)
        throws IOException {
        File yaml = writeYaml(tempDir, "- just a string\n- a: 1\n");

        List<DataRow> rows = reader.read(yaml);

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).getRowIndex());
    }

    @Test
    void emptyListGivesNoRows(@TempDir Path tempDir) throws IOException {
        assertTrue(reader.read(writeYaml(tempDir, "[]\n")).isEmpty());
    }

    @Test
    void rootThatIsNotAListIsRejectedAndNamesTheFile(@TempDir Path tempDir) throws IOException {
        File yaml = writeYaml(tempDir, "a: 1\n");

        DataFileException ex = assertThrows(DataFileException.class, () -> reader.read(yaml));

        assertTrue(ex.getMessage().contains("must be a list"), ex.getMessage());
        assertTrue(ex.getMessage().contains("data.yaml"), ex.getMessage());
    }

    @Test
    void emptyFileIsRejectedBecauseThereIsNoList(@TempDir Path tempDir) throws IOException {
        File yaml = writeYaml(tempDir, "");

        assertThrows(DataFileException.class, () -> reader.read(yaml));
    }

    @Test
    void javaObjectTagsAreNotInstantiated(@TempDir Path tempDir) throws IOException {
        // The reader uses SnakeYAML's SafeConstructor: a data file must never be able to
        // create arbitrary Java objects.
        File yaml = writeYaml(tempDir, "- !!java.lang.ProcessBuilder [[\"true\"]]\n");

        assertThrows(RuntimeException.class, () -> reader.read(yaml));
    }

    @Test
    void missingFileIsReportedAsADataFileProblem(@TempDir Path tempDir) {
        File missing = tempDir.resolve("nope.yaml").toFile();

        DataFileException ex = assertThrows(DataFileException.class, () -> reader.read(missing));

        assertTrue(ex.getMessage().contains("nope.yaml"), ex.getMessage());
    }

    private static File writeYaml(Path tempDir, String content) throws IOException {
        Path file = tempDir.resolve("data.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
