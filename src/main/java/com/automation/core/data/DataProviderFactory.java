package com.automation.core.data;

import com.automation.core.config.ConfigReader;
import com.automation.core.data.synthetic.SyntheticDataProvider;

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

    /**
     * {@code count} freshly generated, realistic-looking registration rows (Faker-backed — see
     * {@code core/data/synthetic/SyntheticDataGenerator}) instead of a hand-written data file.
     * No execute/tags filtering (there's no file, and therefore no such columns to filter on).
     */
    public static Object[][] syntheticRegistrations(int count) {
        List<DataRow> rows = SyntheticDataProvider.registrations(count);
        return DataProvider.toTestNGFormat(rows);
    }

    /**
     * Same as {@link #syntheticRegistrations(int)}, but {@code count} comes from the
     * {@code synthetic.data.count} config key (default 3 — kept deliberately small; see the
     * class-level caveat on {@code RegistrationSyntheticDataTest} about DemoQA's registration
     * endpoint being ReCaptcha-rate-limited) rather than being passed by the caller.
     */
    public static Object[][] syntheticRegistrations() {
        int count = ConfigReader.getInt("synthetic.data.count", 3);
        return syntheticRegistrations(count);
    }

    /**
     * One registration row per boundary/edge-case value (empty, whitespace-only, very long,
     * unicode, injection-shaped, ...) applied to the username field — see
     * {@code SyntheticDataProvider.registrationUsernameEdgeCases()} for the exact value set and
     * why only username varies.
     */
    public static Object[][] syntheticRegistrationEdgeCases() {
        List<DataRow> rows = SyntheticDataProvider.registrationUsernameEdgeCases();
        return DataProvider.toTestNGFormat(rows);
    }
}
