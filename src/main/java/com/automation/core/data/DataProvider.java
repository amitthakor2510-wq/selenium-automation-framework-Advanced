package com.automation.core.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads test data from Excel (.xlsx/.xls), CSV, JSON, or ZIP files.
 *
 * Usage:
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.xlsx");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.csv");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.json");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.zip");
 *
 * For Excel with multiple sheets, use:
 *   List<DataRow> rows = DataProvider.readSheet("path/to/file.xlsx", "Sheet2");
 *
 * First row is always treated as the header row.
 * Empty rows are automatically skipped.
 */
public class DataProvider {

    private static final Logger logger = Logger.getLogger(DataProvider.class.getName());

    private DataProvider() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Auto-detects file type by extension and reads all rows.
     * For ZIP files, reads all supported files inside the archive.
     * For Excel, reads the first sheet.
     *
     * @param filePath absolute or relative path to the data file
     */
    public static List<DataRow> read(String filePath) {
        File file = resolveFile(filePath);
        String name = file.getName().toLowerCase();

        logger.info("[DataProvider] Reading: " + file.getAbsolutePath());

        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return readExcel(file, null);
        } else if (name.endsWith(".csv")) {
            return readCsv(file);
        } else if (name.endsWith(".json")) {
            return readJson(file);
        } else if (name.endsWith(".zip")) {
            return readZip(file);
        } else {
            throw new RuntimeException(
                    "[DataProvider] Unsupported file type: " + name
                            + ". Supported: .xlsx, .xls, .csv, .json, .zip"
            );
        }
    }

    /**
     * Read a specific sheet from an Excel file.
     *
     * @param filePath  path to .xlsx or .xls file
     * @param sheetName exact sheet name (case-sensitive)
     */
    public static List<DataRow> readSheet(String filePath, String sheetName) {
        File file = resolveFile(filePath);
        return readExcel(file, sheetName);
    }

    /**
     * Converts List<DataRow> to Object[][] for TestNG @DataProvider.
     *
     * Usage in test:
     *   \@DataProvider(name = "loginData")
     *   public Object[][] getData() {
     *       return DataProvider.toTestNGFormat(DataProvider.read("path/login.xlsx"));
     *   }
     */
    public static Object[][] toTestNGFormat(List<DataRow> rows) {
        Object[][] result = new Object[rows.size()][1];
        for (int i = 0; i < rows.size(); i++) {
            result[i][0] = rows.get(i);
        }
        return result;
    }

    // ── Excel Reader ──────────────────────────────────────────────────────────

    private static List<DataRow> readExcel(File file, String sheetName) {
        List<DataRow> rows = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
             Workbook workbook = file.getName().endsWith(".xls")
                     ? new HSSFWorkbook(is)
                     : new XSSFWorkbook(is)) {

            Sheet sheet = (sheetName != null)
                    ? workbook.getSheet(sheetName)
                    : workbook.getSheetAt(0);

            if (sheet == null) {
                throw new RuntimeException(
                        "[DataProvider] Sheet '" + sheetName + "' not found in " + file.getName()
                                + ". Available sheets: " + getSheetNames(workbook)
                );
            }

            // First row = headers
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                logger.warning("[DataProvider] Excel file has no header row: " + file.getName());
                return rows;
            }

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValue(cell));
            }

            // Data rows
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isEmptyRow(row)) continue;

                Map<String, String> rowData = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    rowData.put(headers.get(j), getCellValue(cell));
                }
                rows.add(new DataRow(rowData, i));
            }

            logger.info("[DataProvider] Read " + rows.size() + " rows from sheet '"
                    + sheet.getSheetName() + "'");

        } catch (IOException e) {
            throw new RuntimeException("[DataProvider] Failed to read Excel: " + file.getPath(), e);
        }

        return rows;
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    private static boolean isEmptyRow(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !getCellValue(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> getSheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    // ── CSV Reader ────────────────────────────────────────────────────────────

    private static List<DataRow> readCsv(File file) {
        List<DataRow> rows = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new FileReader(file))) {
            List<String[]> all = reader.readAll();
            if (all.isEmpty()) return rows;

            String[] headers = all.get(0);

            for (int i = 1; i < all.size(); i++) {
                String[] rowValues = all.get(i);
                if (isEmptyArray(rowValues)) continue;

                Map<String, String> rowData = new LinkedHashMap<>();
                for (int j = 0; j < headers.length; j++) {
                    String value = (j < rowValues.length) ? rowValues[j] : "";
                    rowData.put(headers[j], value);
                }
                rows.add(new DataRow(rowData, i));
            }

            logger.info("[DataProvider] Read " + rows.size() + " rows from CSV: " + file.getName());

        } catch (Exception e) {
            throw new RuntimeException("[DataProvider] Failed to read CSV: " + file.getPath(), e);
        }

        return rows;
    }

    private static boolean isEmptyArray(String[] arr) {
        if (arr == null) return true;
        for (String s : arr) {
            if (s != null && !s.trim().isEmpty()) return false;
        }
        return true;
    }

    // ── JSON Reader ───────────────────────────────────────────────────────────

    /**
     * Expects a JSON array of objects:
     * [
     *   { "username": "john", "password": "pass123", "expected": "success" },
     *   { "username": "locked", "password": "pass123", "expected": "error" }
     * ]
     */
    private static List<DataRow> readJson(File file) {
        List<DataRow> rows = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, String>> list = mapper.readValue(
                    file,
                    new TypeReference<List<Map<String, String>>>() {}
            );

            for (int i = 0; i < list.size(); i++) {
                rows.add(new DataRow(list.get(i), i + 1));
            }

            logger.info("[DataProvider] Read " + rows.size() + " rows from JSON: " + file.getName());

        } catch (IOException e) {
            throw new RuntimeException("[DataProvider] Failed to read JSON: " + file.getPath(), e);
        }

        return rows;
    }

    // ── ZIP Reader ────────────────────────────────────────────────────────────

    /**
     * Reads all supported files (.xlsx, .xls, .csv, .json) inside the ZIP.
     * All rows from all files are combined into one list.
     */
    private static List<DataRow> readZip(File zipFile) {
        List<DataRow> allRows = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            int rowOffset = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().toLowerCase();

                // Skip directories and unsupported files
                if (entry.isDirectory()
                        || (!entryName.endsWith(".xlsx")
                        && !entryName.endsWith(".xls")
                        && !entryName.endsWith(".csv")
                        && !entryName.endsWith(".json"))) {
                    zis.closeEntry();
                    continue;
                }

                logger.info("[DataProvider] Reading from ZIP entry: " + entry.getName());

                // Extract entry to a temp file
                File tempFile = extractToTemp(zis, entry.getName());
                List<DataRow> rows = read(tempFile.getAbsolutePath());

                // Offset row indices to avoid duplicates across files
                for (DataRow row : rows) {
                    allRows.add(new DataRow(row.toMap(), row.getRowIndex() + rowOffset));
                }
                rowOffset += rows.size();

                tempFile.deleteOnExit();
                zis.closeEntry();
            }

        } catch (IOException e) {
            throw new RuntimeException("[DataProvider] Failed to read ZIP: " + zipFile.getPath(), e);
        }

        logger.info("[DataProvider] Total rows read from ZIP: " + allRows.size());
        return allRows;
    }

    private static File extractToTemp(ZipInputStream zis, String entryName) throws IOException {
        String ext = entryName.substring(entryName.lastIndexOf('.'));
        File tempFile = File.createTempFile("ddt_", ext);

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
        return tempFile;
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    private static File resolveFile(String filePath) {
        // Try as absolute path first
        File file = new File(filePath);
        if (file.exists()) return file;

        // Try relative to project root
        file = new File(System.getProperty("user.dir"), filePath);
        if (file.exists()) return file;

        // Try on classpath (inside src/test/resources)
        try {
            var resource = DataProvider.class.getClassLoader().getResource(filePath);
            if (resource != null) return new File(resource.toURI());
        } catch (Exception ignored) {}

        throw new RuntimeException(
                "[DataProvider] File not found: " + filePath
                        + "\nTried: absolute path, project-relative, and classpath."
        );
    }
}