package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads .yaml/.yml test data. Expects a YAML list of flat mappings, e.g.:
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
public class YamlDataFileReader implements DataFileReader {

    private static final Logger logger = Logger.getLogger(YamlDataFileReader.class.getName());

    @Override
    @SuppressWarnings("unchecked")
    public List<DataRow> read(File file) {
        List<DataRow> rows = new ArrayList<>();

        try (InputStream is = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(is);

            if (!(loaded instanceof List)) {
                throw new RuntimeException(
                    "[YamlDataFileReader] YAML root must be a list of rows: " + file.getName());
            }

            List<Object> list = (List<Object>) loaded;
            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (!(entry instanceof Map)) {
                    continue;
                }

                Map<String, String> rowData = new LinkedHashMap<>();
                ((Map<Object, Object>) entry).forEach((k, v) ->
                    rowData.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));

                rows.add(new DataRow(rowData, i + 1));
            }

            logger.info("[YamlDataFileReader] Read " + rows.size() + " rows from YAML: " + file.getName());

        } catch (IOException e) {
            throw new RuntimeException("[YamlDataFileReader] Failed to read YAML: " + file.getPath(), e);
        }

        return rows;
    }
}
