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

    /**
     * Rows filtered by the "execute" column and, if -Ddata.tags is set,
     * by the "tags" column too. This is the one you want by default.
     */
    public static Object[][] fromFile(String filePath) {
        List<DataRow> rows = DataProvider.read(filePath);
        return DataProvider.toTestNGFormat(rows);
    }

    public static Object[][] fromSheet(String filePath, String sheetName) {
        List<DataRow> rows = DataProvider.readSheet(filePath, sheetName);
        return DataProvider.toTestNGFormat(rows);
    }

    /**
     * Every row in the file, ignoring the execute/tags filters. Useful for
     * a data-audit test, or when the caller wants full control.
     */
    public static Object[][] fromFileUnfiltered(String filePath) {
        List<DataRow> rows = DataProvider.readAll(filePath);
        return DataProvider.toTestNGFormat(rows);
    }

    /**
     * Rows whose "tags" column matches one of the given tags, regardless of
     * the -Ddata.tags system property. Still honours the "execute" column.
     */
    public static Object[][] fromFileWithTags(String filePath, String... tags) {
        List<DataRow> rows = DataProvider.readWithTags(filePath, tags);
        return DataProvider.toTestNGFormat(rows);
    }
}
