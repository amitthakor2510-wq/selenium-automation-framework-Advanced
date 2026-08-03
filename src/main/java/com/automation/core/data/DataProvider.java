package com.automation.core.data;

import com.automation.core.config.ConfigReader;
import com.automation.core.data.readers.DataFileReaderRegistry;
import com.automation.core.exceptions.DataFileException;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

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
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────
 * The actual per-format parsing (Excel/CSV/JSON/YAML/ZIP) lives in
 * com.automation.core.data.readers — one class per format behind the
 * DataFileReader interface, picked by DataFileReaderRegistry based on file
 * extension. This class only does two things: resolve the file path, and
 * apply the DDT execute/tags filtering below. Previously all of that
 * (five file formats + filtering + path resolution) lived in this one
 * class; splitting it out means adding a new format no longer means
 * editing this file at all.
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
    private static final DataFileReaderRegistry REGISTRY = new DataFileReaderRegistry();

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
        return REGISTRY.readAll(file);
    }

    /**
     * Read a specific sheet from an Excel file (execute/tags filters applied).
     *
     * @param filePath  path to .xlsx or .xls file
     * @param sheetName exact sheet name (case-sensitive)
     */
    public static List<DataRow> readSheet(String filePath, String sheetName) {
        File file = resolveFile(filePath);
        return filterRows(REGISTRY.readExcelSheet(file, sheetName));
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
        for (String t : tags) {
            wanted.add(t.trim().toLowerCase());
        }
        List<DataRow> rows = new ArrayList<>();
        for (DataRow row : filterExecuteOnly(readAll(filePath))) {
            if (rowMatchesTags(row, wanted)) {
                rows.add(row);
            }
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

    // ── Row filtering (DDT: execute column + tag selection) ─────────────────────

    private static final Set<String> FALSY = Set.of("no", "false", "0", "skip", "n");

    private static List<DataRow> filterRows(List<DataRow> rows) {
        List<DataRow> byExecute = filterExecuteOnly(rows);

        Set<String> requestedTags = requestedTags();
        if (requestedTags.isEmpty()) {
            return byExecute;
        }

        List<DataRow> byTags = new ArrayList<>();
        for (DataRow row : byExecute) {
            if (rowMatchesTags(row, requestedTags)) {
                byTags.add(row);
            }
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
        if (raw == null || raw.trim().isEmpty()) {
            return tags;
        }
        for (String t : raw.split(",")) {
            if (!t.trim().isEmpty()) {
                tags.add(t.trim().toLowerCase());
            }
        }
        return tags;
    }

    private static boolean rowMatchesTags(DataRow row, Set<String> wanted) {
        if (wanted.isEmpty()) {
            return true;
        }
        if (!row.has("tags")) {
            return false;
        }
        String raw = row.get("tags");
        for (String candidate : raw.split("[,|]")) {
            if (wanted.contains(candidate.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    private static File resolveFile(String filePath) {
        // Try as absolute path first
        File file = new File(filePath);
        if (file.exists()) {
            return file;
        }

        // Try relative to project root
        file = new File(System.getProperty("user.dir"), filePath);
        if (file.exists()) {
            return file;
        }

        // Try on classpath (inside src/test/resources)
        try {
            var resource = DataProvider.class.getClassLoader().getResource(filePath);
            if (resource != null) {
                return new File(resource.toURI());
            }
        } catch (Exception ignored) {
            // fall through to "not found" below
        }

        throw new DataFileException(
            "[DataProvider] File not found: " + filePath
                + "\nTried: absolute path, project-relative, and classpath."
        );
    }
}
