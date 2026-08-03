package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads all supported files (.xlsx, .xls, .csv, .json) inside a ZIP archive
 * and combines their rows into one list. Delegates each extracted entry
 * back through DataFileReaderRegistry instead of duplicating per-format
 * parsing logic here.
 */
public class ZipDataFileReader implements DataFileReader {

    private static final Logger logger = Logger.getLogger(ZipDataFileReader.class.getName());

    private final DataFileReaderRegistry registry;

    public ZipDataFileReader(DataFileReaderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<DataRow> read(File zipFile) {
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

                logger.info("[ZipDataFileReader] Reading from ZIP entry: " + entry.getName());

                // Extract entry to a temp file
                File tempFile = extractToTemp(zis, entry.getName());
                List<DataRow> rows = registry.readAll(tempFile);

                for (DataRow row : rows) {
                    runningIndex++;
                    allRows.add(new DataRow(row.toMap(), runningIndex));
                }

                tempFile.deleteOnExit();
                zis.closeEntry();
            }

        } catch (IOException e) {
            throw new DataFileException("[ZipDataFileReader] Failed to read ZIP: " + zipFile.getPath(), e);
        }

        logger.info("[ZipDataFileReader] Total rows read from ZIP: " + allRows.size());
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
}
