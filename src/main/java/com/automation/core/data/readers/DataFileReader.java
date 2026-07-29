package com.automation.core.data.readers;

import com.automation.core.data.DataRow;

import java.io.File;
import java.util.List;

/**
 * One implementation per supported test-data file format (Excel, CSV, JSON,
 * YAML, ZIP). Extracted out of what used to be a single 570-line
 * DataProvider class handling every format itself — adding a new format
 * now means adding one new class here and registering it in
 * DataFileReaderRegistry, instead of editing a monolith that already knew
 * about five unrelated file formats.
 */
public interface DataFileReader {

    /** Reads every row from the file, unfiltered — DDT execute/tags filtering happens in DataProvider. */
    List<DataRow> read(File file);
}
