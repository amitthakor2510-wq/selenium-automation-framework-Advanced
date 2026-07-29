package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads .json test data. Expects a JSON array of objects:
 * [
 *   { "username": "john", "password": "pass123", "expected": "success" },
 *   { "username": "locked", "password": "pass123", "expected": "error" }
 * ]
 */
public class JsonDataFileReader implements DataFileReader {

    private static final Logger logger = Logger.getLogger(JsonDataFileReader.class.getName());

    @Override
    public List<DataRow> read(File file) {
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

            logger.info("[JsonDataFileReader] Read " + rows.size() + " rows from JSON: " + file.getName());

        } catch (IOException e) {
            throw new RuntimeException("[JsonDataFileReader] Failed to read JSON: " + file.getPath(), e);
        }

        return rows;
    }
}
