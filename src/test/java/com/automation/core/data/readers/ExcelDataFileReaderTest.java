package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workbooks are built with Apache POI into a temp dir and read back through the real reader. */
class ExcelDataFileReaderTest {

    private final ExcelDataFileReader reader = new ExcelDataFileReader();

    @Test
    void readsTheFirstSheetHeaderAndRows(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet sheet = wb.createSheet("Sheet1");
            fill(sheet, 0, "username", "password");
            fill(sheet, 1, "john", "pass123");
            fill(sheet, 2, "jane", "pass456");
        });

        List<DataRow> rows = reader.read(file);

        assertEquals(2, rows.size());
        assertEquals("john", rows.get(0).get("username"));
        assertEquals("pass456", rows.get(1).get("password"));
        assertEquals(1, rows.get(0).getRowIndex());
        assertEquals(2, rows.get(1).getRowIndex());
    }

    @Test
    void numericCellsAreReadAsTheTextTheyDisplay(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet sheet = wb.createSheet("Sheet1");
            fill(sheet, 0, "qty", "label");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(30);
            row.createCell(1).setCellValue("  padded  ");
        });

        DataRow row = reader.read(file).get(0);

        assertEquals("30", row.get("qty"));
        assertEquals("padded", row.get("label"));
    }

    @Test
    void blankHeaderCellDoesNotShiftTheColumnsAfterIt(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("a");
            header.createCell(2).setCellValue("c");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("a1");
            data.createCell(1).setCellValue("stray");
            data.createCell(2).setCellValue("c1");
        });

        DataRow row = reader.read(file).get(0);

        assertEquals("a1", row.get("a"));
        assertEquals("c1", row.get("c"));
        assertEquals(List.of("a", "c"), List.copyOf(row.toMap().keySet()));
    }

    @Test
    void emptyRowsAreSkippedButKeepTheirPosition(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet sheet = wb.createSheet("Sheet1");
            fill(sheet, 0, "a");
            fill(sheet, 1, "first");
            fill(sheet, 3, "third");
        });

        List<DataRow> rows = reader.read(file);

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).getRowIndex());
        assertEquals(3, rows.get(1).getRowIndex());
    }

    @Test
    void missingCellsInADataRowReadAsEmptyStrings(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet sheet = wb.createSheet("Sheet1");
            fill(sheet, 0, "a", "b");
            fill(sheet, 1, "only-a");
        });

        DataRow row = reader.read(file).get(0);

        assertEquals("only-a", row.get("a"));
        assertTrue(row.has("b"));
        assertEquals("", row.get("b"));
    }

    @Test
    void readSheetPicksTheNamedSheet(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> {
            Sheet first = wb.createSheet("First");
            fill(first, 0, "a");
            fill(first, 1, "from-first");
            Sheet second = wb.createSheet("Second");
            fill(second, 0, "a");
            fill(second, 1, "from-second");
        });

        assertEquals("from-first", reader.read(file).get(0).get("a"));
        assertEquals("from-second", reader.readSheet(file, "Second").get(0).get("a"));
    }

    @Test
    void unknownSheetNameListsTheSheetsThatDoExist(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> fill(wb.createSheet("Sheet1"), 0, "a"));

        DataFileException ex = assertThrows(DataFileException.class,
            () -> reader.readSheet(file, "Nope"));

        assertTrue(ex.getMessage().contains("Sheet 'Nope' not found"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[Sheet1]"), ex.getMessage());
    }

    @Test
    void sheetWithoutAnyRowsGivesNoDataRows(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> wb.createSheet("Sheet1"));

        assertTrue(reader.read(file).isEmpty());
    }

    @Test
    void sheetWithOnlyAHeaderGivesNoDataRows(@TempDir Path tempDir) throws IOException {
        File file = xlsx(tempDir, wb -> fill(wb.createSheet("Sheet1"), 0, "a", "b"));

        assertTrue(reader.read(file).isEmpty());
    }

    @Test
    void legacyXlsFilesAreReadToo(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("data.xls");
        try (Workbook wb = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(path)) {
            Sheet sheet = wb.createSheet("Sheet1");
            fill(sheet, 0, "a");
            fill(sheet, 1, "legacy");
            wb.write(out);
        }

        List<DataRow> rows = reader.read(path.toFile());

        assertEquals(1, rows.size());
        assertEquals("legacy", rows.get(0).get("a"));
    }

    @Test
    void missingFileIsReportedAsADataFileProblem(@TempDir Path tempDir) {
        File missing = tempDir.resolve("nope.xlsx").toFile();

        DataFileException ex = assertThrows(DataFileException.class, () -> reader.read(missing));

        assertTrue(ex.getMessage().contains("nope.xlsx"), ex.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private interface WorkbookBuilder {
        void build(Workbook workbook);
    }

    private static File xlsx(Path tempDir, WorkbookBuilder builder) throws IOException {
        Path path = tempDir.resolve("data.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(path)) {
            builder.build(wb);
            wb.write(out);
        }
        return path.toFile();
    }

    private static void fill(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
