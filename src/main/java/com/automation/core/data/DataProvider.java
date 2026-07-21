package com.automation.core.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.yaml.snakeyaml.Yaml;

import com.automation.core.config.ConfigReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads test data from Excel (.xlsx/.xls), CSV, JSON, YAML, or ZIP files.
 *
 * Usage:
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.xlsx");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.csv");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.json");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.yaml");
 *   List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.zip");
 *
 * For Excel with multiple sheets, use:
 *   List<DataRow> rows = DataProvider.readSheet("path/to/file.xlsx", "Sheet2");
 *
 * First row is always treated as the header row (Excel/CSV). JSON/YAML are
 * lists of flat objects, one object per test data row.
 *
 * ── ROW FILTERING (DDT enhancement) ─────────────────────────────────────────
 * Every read()/readSheet() call automatically applies two optional filters,
 * driven by columns in the data file itself so QA can turn rows on/off or
 * target a subset without touching Java code:
 *
 *   execute  — column named by config key `data.execute.column` (default
 *              "execute"). Rows where this resolves to no/false/0/skip
 *              (case-insensitive) are excluded and logged, not sent to the
 *              test. Missing column or blank value = row runs.
 *
 *   tags     — column named "tags" (comma/pipe separated, e.g. "smoke,regression").
 *              When -Ddata.tags=smoke,sanity is set (or data.tags in a
 *              properties file), only rows whose tags intersect that set
 *              run. Rows with no tags column, or run when data.tags is blank.
 *
 * Use readAll(filePath) if you need the unfiltered rows (e.g. to report on
 * how many were skipped).
 */
public class DataProvider {

    private static final Logger logger = Logger.getLogger(DataProvider.class.getName());

    private DataProvider() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Auto-detects file type by extension, reads all rows, and applies the
     * execute/tags filters described above.
     * For ZIP files, reads all supported files inside the archive.
     * For Excel, reads the first sheet.
     *
     * @param filePath absolute or relative path to the data file
     */
    public static List<DataRow> read(String filePath) {
        return filterRows(readAll(filePath));
    }

    /**
     * Same as read(filePath) but skips the execute/tags filtering — returns
     * every row exactly as stored in the file. Useful for auditing data
     * files or when a caller wants to apply its own filtering logic.
     */
    public static List<DataRow> readAll(String filePath) {
        File file = resolveFile(filePath);
        String name = file.getName().toLowerCase();

        logger.info("[DataProvider] Reading: " + file.getAbsolutePath());

        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return readExcel(file, null);
        } else if (name.endsWith(".csv")) {
            return readCsv(file);
        } else if (name.endsWith(".json")) {
            return readJson(file);
        } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return readYaml(file);
        } else if (name.endsWith(".zip")) {
            return readZip(file);
        } else {
            throw new RuntimeException(
                    "[DataProvider] Unsupported file type: " + name
                            + ". Supported: .xlsx, .xls, .csv, .json, .yaml, .yml, .zip"
            );
        }
    }

    /**
     * Read a specific sheet from an Excel file (execute/tags filters applied).
     *
     * @param filePath  path to .xlsx or .xls file
     * @param sheetName exact sheet name (case-sensitive)
     */
    public static List<DataRow> readSheet(String filePath, String sheetName) {
        File file = resolveFile(filePath);
        return filterRows(readExcel(file, sheetName));
    }

    /**
     * Read rows and keep only those matching an explicit tag set, ignoring
     * whatever -Ddata.tags is currently set to. Useful for calling the same
     * data file from two different tests that need different subsets.
     *
     * @param filePath path to the data file
     * @param tags     tags to match against each row's "tags" column (OR match)
     */
    public static List<DataRow> readWithTags(String filePath, String... tags) {
        Set<String> wanted = new HashSet<>();
        for (String t : tags) wanted.add(t.trim().toLowerCase());
        List<DataRow> rows = new ArrayList<>();
        for (DataRow row : filterExecuteOnly(readAll(filePath))) {
            if (rowMatchesTags(row, wanted)) rows.add(row);
        }
        return rows;
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

        // Read explicitly as UTF-8 instead of relying on the JVM's platform
        // default charset (new FileReader(file)), which corrupts non-ASCII
        // data (accented names, currency symbols, etc.) on machines whose
        // default charset isn't UTF-8 (e.g. some Windows CI agents).
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

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

    // ── YAML Reader ───────────────────────────────────────────────────────────

    /**
     * Expects a YAML list of flat mappings, e.g.:
     *
     * - username: john
     *   password: pass123
     *   expected: success
     *   tags: smoke, regression
     * - username: locked
     *   password: pass123
     *   expected: error
     *   execute: "no"
     */
    @SuppressWarnings("unchecked")
    private static List<DataRow> readYaml(File file) {
        List<DataRow> rows = new ArrayList<>();

        try (InputStream is = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(is);

            if (!(loaded instanceof List)) {
                throw new RuntimeException(
                        "[DataProvider] YAML root must be a list of rows: " + file.getName());
            }

            List<Object> list = (List<Object>) loaded;
            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (!(entry instanceof Map)) continue;

                Map<String, String> rowData = new LinkedHashMap<>();
                ((Map<Object, Object>) entry).forEach((k, v) ->
                        rowData.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));

                rows.add(new DataRow(rowData, i + 1));
            }

            logger.info("[DataProvider] Read " + rows.size() + " rows from YAML: " + file.getName());

        } catch (IOException e) {
            throw new RuntimeException("[DataProvider] Failed to read YAML: " + file.getPath(), e);
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

            // Running counter for globally-unique row indices across all files
            // in the zip. NOTE: previously this used an accumulated
            // "rowOffset += rows.size()", which is wrong whenever a source
            // file skips blank rows — getRowIndex() reflects the row's real
            // position in its sheet/CSV, so adding a row *count* (not the max
            // index actually consumed) can produce duplicate/misleading row
            // numbers across files. A simple running counter avoids that.
            int runningIndex = 0;

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
                List<DataRow> rows = readAll(tempFile.getAbsolutePath());

                for (DataRow row : rows) {
                    runningIndex++;
                    allRows.add(new DataRow(row.toMap(), runningIndex));
                }

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

    // ── Row filtering (DDT: execute column + tag selection) ─────────────────────

    private static final Set<String> FALSY = Set.of("no", "false", "0", "skip", "n");

    private static List<DataRow> filterRows(List<DataRow> rows) {
        List<DataRow> byExecute = filterExecuteOnly(rows);

        Set<String> requestedTags = requestedTags();
        if (requestedTags.isEmpty()) return byExecute;

        List<DataRow> byTags = new ArrayList<>();
        for (DataRow row : byExecute) {
            if (rowMatchesTags(row, requestedTags)) byTags.add(row);
        }
        logger.info("[DataProvider] Tag filter " + requestedTags + " matched "
                + byTags.size() + "/" + byExecute.size() + " rows");
        return byTags;
    }

    private static List<DataRow> filterExecuteOnly(List<DataRow> rows) {
        String executeColumn = ConfigReader.get("data.execute.column", "execute");
        List<DataRow> kept = new ArrayList<>();
        int skipped = 0;

        for (DataRow row : rows) {
            if (row.has(executeColumn)) {
                String value = row.get(executeColumn).trim().toLowerCase();
                if (!value.isEmpty() && FALSY.contains(value)) {
                    skipped++;
                    logger.info("[DataProvider] Skipping row " + row.getRowIndex()
                            + " (" + executeColumn + "=" + value + ")");
                    continue;
                }
            }
            kept.add(row);
        }

        if (skipped > 0) {
            logger.info("[DataProvider] Excluded " + skipped + " row(s) via '"
                    + executeColumn + "' column");
        }
        return kept;
    }

    private static Set<String> requestedTags() {
        String raw = ConfigReader.get("data.tags", "");
        Set<String> tags = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return tags;
        for (String t : raw.split(",")) {
            if (!t.trim().isEmpty()) tags.add(t.trim().toLowerCase());
        }
        return tags;
    }

    private static boolean rowMatchesTags(DataRow row, Set<String> wanted) {
        if (wanted.isEmpty()) return true;
        if (!row.has("tags")) return false;
        String raw = row.get("tags");
        for (String candidate : raw.split("[,|]")) {
            if (wanted.contains(candidate.trim().toLowerCase())) return true;
        }
        return false;
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