package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

/**
 * Picks the right DataFileReader for a file by extension. This is the one
 * place that knows "which reader handles which extension" — adding a new
 * format means adding one case here and one new DataFileReader class,
 * instead of editing a single class that already knew about five unrelated
 * formats (what DataProvider used to be).
 */
public final class DataFileReaderRegistry {

    private static final Logger logger = Logger.getLogger(DataFileReaderRegistry.class.getName());

    private final ExcelDataFileReader excelReader = new ExcelDataFileReader();
    private final CsvDataFileReader csvReader = new CsvDataFileReader();
    private final JsonDataFileReader jsonReader = new JsonDataFileReader();
    private final YamlDataFileReader yamlReader = new YamlDataFileReader();
    private final ZipDataFileReader zipReader = new ZipDataFileReader(this);

    /** True if this registry has a reader for the given file's extension. */
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        // BUG FIX: .zip was handled by readAll()/ZipDataFileReader but was
        // missing here, so supports() incorrectly reported false for a file
        // type this registry actually reads — any caller that gates on
        // supports() before calling readAll() would wrongly reject valid
        // zip data files.
        return name.endsWith(".xlsx") || name.endsWith(".xls")
            || name.endsWith(".csv")
            || name.endsWith(".json")
            || name.endsWith(".yaml") || name.endsWith(".yml")
            || name.endsWith(".zip");
    }

    /** Reads a file, auto-detecting format by extension. ZIP files are read via ZipDataFileReader. */
    public List<DataRow> readAll(File file) {
        String name = file.getName().toLowerCase();

        logger.info("[DataFileReaderRegistry] Reading: " + file.getAbsolutePath());

        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return excelReader.read(file);
        } else if (name.endsWith(".csv")) {
            return csvReader.read(file);
        } else if (name.endsWith(".json")) {
            return jsonReader.read(file);
        } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return yamlReader.read(file);
        } else if (name.endsWith(".zip")) {
            return zipReader.read(file);
        } else {
            throw new DataFileException(
                "[DataFileReaderRegistry] Unsupported file type: " + name
                    + ". Supported: .xlsx, .xls, .csv, .json, .yaml, .yml, .zip"
            );
        }
    }

    /** Reads a specific Excel sheet by name — the one format-specific extra the generic API doesn't cover. */
    public List<DataRow> readExcelSheet(File file, String sheetName) {
        return excelReader.readSheet(file, sheetName);
    }
}
