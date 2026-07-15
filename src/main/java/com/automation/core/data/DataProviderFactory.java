package com.automation.core.data;

import java.util.List;

/**
 * Convenience factory — gives you TestNG-ready Object[][] directly.
 *
 * Usage in test:
 *   \@DataProvider(name = "loginData")
 *   public Object[][] getData() {
 *       return DataProviderFactory.fromFile("src/test/resources/testdata/login.xlsx");
 *   }
 */
public class DataProviderFactory {

    private DataProviderFactory() {}

    public static Object[][] fromFile(String filePath) {
        List<DataRow> rows = DataProvider.read(filePath);
        return DataProvider.toTestNGFormat(rows);
    }

    public static Object[][] fromSheet(String filePath, String sheetName) {
        List<DataRow> rows = DataProvider.readSheet(filePath, sheetName);
        return DataProvider.toTestNGFormat(rows);
    }
}