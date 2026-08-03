package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import com.opencsv.CSVReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Reads .csv test data. First row is always the header row. */
public class CsvDataFileReader implements DataFileReader {

    private static final Logger logger = Logger.getLogger(CsvDataFileReader.class.getName());

    @Override
    public List<DataRow> read(File file) {
        List<DataRow> rows = new ArrayList<>();

        // Read explicitly as UTF-8 instead of relying on the JVM's platform
        // default charset (new FileReader(file)), which corrupts non-ASCII
        // data (accented names, currency symbols, etc.) on machines whose
        // default charset isn't UTF-8 (e.g. some Windows CI agents).
        try (CSVReader reader = new CSVReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            List<String[]> all = reader.readAll();
            if (all.isEmpty()) {
                return rows;
            }

            String[] headers = all.get(0);

            for (int i = 1; i < all.size(); i++) {
                String[] rowValues = all.get(i);
                if (isEmptyArray(rowValues)) {
                    continue;
                }

                Map<String, String> rowData = new LinkedHashMap<>();
                for (int j = 0; j < headers.length; j++) {
                    String value = (j < rowValues.length) ? rowValues[j] : "";
                    rowData.put(headers[j], value);
                }
                rows.add(new DataRow(rowData, i));
            }

            logger.info("[CsvDataFileReader] Read " + rows.size() + " rows from CSV: " + file.getName());

        } catch (Exception e) {
            throw new DataFileException("[CsvDataFileReader] Failed to read CSV: " + file.getPath(), e);
        }

        return rows;
    }

    private static boolean isEmptyArray(String[] arr) {
        if (arr == null) {
            return true;
        }
        for (String s : arr) {
            if (s != null && !s.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
