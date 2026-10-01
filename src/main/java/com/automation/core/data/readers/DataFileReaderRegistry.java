package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the right DataFileReader for a file by extension. This is the one
 * place that knows "which reader handles which extension" — adding a new
 * format means adding one new DataFileReader class and one line to
 * readersByExtension below, instead of editing a single class that
 * already knew about five unrelated formats (what DataProvider used to
 * be) — or, as this class itself was until now, an if/else-if chain
 * duplicated across supports() and readAll() that had to be kept in sync
 * by hand (a real .zip-support bug here was exactly what that duplication
 * caused, before this fix). Same registry-over-branching approach as
 * DriverFactory's BrowserProvider.
 */
public final class DataFileReaderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DataFileReaderRegistry.class);

    private final ExcelDataFileReader excelReader = new ExcelDataFileReader();
    private final CsvDataFileReader csvReader = new CsvDataFileReader();
    private final JsonDataFileReader jsonReader = new JsonDataFileReader();
    private final YamlDataFileReader yamlReader = new YamlDataFileReader();
    private final ZipDataFileReader zipReader = new ZipDataFileReader(this);

    // Extension (lowercase, no dot) -> the one reader that handles it. Two
    // extensions can map to the same reader instance (xlsx/xls, yaml/yml)
    // — that's the whole point of a Map over an if/else chain: each
    // extension is declared exactly once, here, instead of once per
    // branch in two separate methods.
    private final Map<String, DataFileReader> readersByExtension = new HashMap<>();

    {
        readersByExtension.put("xlsx", excelReader);
        readersByExtension.put("xls", excelReader);
        readersByExtension.put("csv", csvReader);
        readersByExtension.put("json", jsonReader);
        readersByExtension.put("yaml", yamlReader);
        readersByExtension.put("yml", yamlReader);
        readersByExtension.put("zip", zipReader);
    }

    /** True if this registry has a reader for the given file's extension. */
    public boolean supports(File file) {
        return readersByExtension.containsKey(extensionOf(file));
    }

    /** Reads a file, auto-detecting format by extension. ZIP files are read via ZipDataFileReader. */
    public List<DataRow> readAll(File file) {
        String extension = extensionOf(file);
        DataFileReader reader = readersByExtension.get(extension);
        if (reader == null) {
            throw new DataFileException(
                "[DataFileReaderRegistry] Unsupported file type: " + file.getName()
                    + ". Supported: " + String.join(", ", readersByExtension.keySet())
            );
        }

        logger.info("[DataFileReaderRegistry] Reading: " + file.getAbsolutePath());
        return reader.read(file);
    }

    /** Reads a specific Excel sheet by name — the one format-specific extra the generic API doesn't cover. */
    public List<DataRow> readExcelSheet(File file, String sheetName) {
        return excelReader.readSheet(file, sheetName);
    }

    private static String extensionOf(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
