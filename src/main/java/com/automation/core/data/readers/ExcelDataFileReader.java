package com.automation.core.data.readers;

import com.automation.core.data.DataRow;
import com.automation.core.exceptions.DataFileException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads .xlsx/.xls test data. First row is always the header row. */
public class ExcelDataFileReader implements DataFileReader {

    private static final Logger logger = LoggerFactory.getLogger(ExcelDataFileReader.class);

    /** Reads the first sheet — used by the generic read() entry point. */
    @Override
    public List<DataRow> read(File file) {
        return readSheet(file, null);
    }

    /** Reads a specific sheet by name (null = first sheet). */
    public List<DataRow> readSheet(File file, String sheetName) {
        List<DataRow> rows = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
             Workbook workbook = file.getName().endsWith(".xls")
                 ? new HSSFWorkbook(is)
                 : new XSSFWorkbook(is)) {

            Sheet sheet = (sheetName != null)
                ? workbook.getSheet(sheetName)
                : workbook.getSheetAt(0);

            if (sheet == null) {
                throw new DataFileException(
                    "[ExcelDataFileReader] Sheet '" + sheetName + "' not found in " + file.getName()
                        + ". Available sheets: " + getSheetNames(workbook)
                );
            }

            // First row = headers
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                logger.warn("[ExcelDataFileReader] Excel file has no header row: " + file.getName());
                return rows;
            }

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValue(cell));
            }

            // Data rows
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isEmptyRow(row)) {
                    continue;
                }

                Map<String, String> rowData = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    rowData.put(headers.get(j), getCellValue(cell));
                }
                rows.add(new DataRow(rowData, i));
            }

            logger.info("[ExcelDataFileReader] Read " + rows.size() + " rows from sheet '"
                + sheet.getSheetName() + "'");

        } catch (IOException e) {
            throw new DataFileException("[ExcelDataFileReader] Failed to read Excel: " + file.getPath(), e);
        }

        return rows;
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    private static boolean isEmptyRow(Row row) {
        if (row == null) {
            return true;
        }
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
}
