package com.automation.core.data;

import com.automation.core.exceptions.DataFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately narrow, pure-logic unit tests — no WebDriver, no
 * ConfigReader/ThreadLocal state, no file I/O. This is the intended shape
 * of test for mutation testing (see the "mutation" Maven profile in
 * pom.xml): fast enough to re-run per-mutant, and specific enough that a
 * mutant surviving one of these actually means something (a coverage-only
 * test that never asserts the *value* would pass against almost any
 * mutation of this class's boolean/comparison logic).
 */
class DataRowTest {

    @Test
    void getIsCaseInsensitiveOnColumnName() {
        DataRow row = rowOf("Username", "john");
        assertEquals("john", row.get("username"));
        assertEquals("john", row.get("USERNAME"));
        assertEquals("john", row.get("UsErNaMe"));
    }

    @Test
    void getTrimsBothStoredValueAndLookupKey() {
        DataRow row = rowOf("  username  ", "  john  ");
        assertEquals("john", row.get("username"));
        assertEquals("john", row.get("  username  "));
    }

    @Test
    void getReturnsEmptyStringForMissingColumn() {
        DataRow row = rowOf("username", "john");
        assertEquals("", row.get("password"));
    }

    @Test
    void nullValueIsStoredAsEmptyStringNotNull() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("username", null);
        DataRow row = new DataRow(data, 1);
        assertEquals("", row.get("username"));
    }

    @Test
    void hasReturnsTrueOnlyForPresentColumnRegardlessOfCase() {
        DataRow row = rowOf("username", "john");
        assertTrue(row.has("username"));
        assertTrue(row.has("USERNAME"));
        assertFalse(row.has("password"));
    }

    @Test
    void hasReturnsTrueEvenWhenValueIsEmpty() {
        // has() checks key presence, not value emptiness — getRequired()
        // below is the one that treats an empty value as "missing".
        DataRow row = rowOf("username", "");
        assertTrue(row.has("username"));
    }

    @Test
    void getRequiredReturnsValueWhenPresentAndNonEmpty() {
        DataRow row = rowOf("username", "john");
        assertEquals("john", row.getRequired("username"));
    }

    @Test
    void getRequiredThrowsWhenColumnMissingEntirely() {
        DataRow row = rowOf("username", "john");
        DataFileException ex = assertThrows(DataFileException.class,
            () -> row.getRequired("password"));
        assertTrue(ex.getMessage().contains("password"));
    }

    @Test
    void getRequiredThrowsWhenColumnPresentButEmpty() {
        DataRow row = rowOf("username", "   ");
        assertThrows(DataFileException.class, () -> row.getRequired("username"));
    }

    @Test
    void rowIndexIsPreservedExactly() {
        DataRow row = rowOf("username", "john", 7);
        assertEquals(7, row.getRowIndex());
    }

    @Test
    void toMapReturnsACopyNotTheLiveInternalMap() {
        DataRow row = rowOf("username", "john");
        Map<String, String> copy = row.toMap();
        copy.put("username", "mutated");
        // The row's own get() must be unaffected by mutating the returned map.
        assertEquals("john", row.get("username"));
    }

    @Test
    void preservingWhitespaceKeepsValuesExactlyButStillNormalizesKeys() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("  UserName ", "   ");
        data.put("padded", "  x  ");
        DataRow row = DataRow.preservingWhitespace(data, 3);

        assertEquals("   ", row.get("username"));
        assertEquals("  x  ", row.get("padded"));
        assertEquals(3, row.getRowIndex());
    }

    @Test
    void preservingWhitespaceStillMapsNullToEmptyString() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("username", null);
        assertEquals("", DataRow.preservingWhitespace(data, 1).get("username"));
    }

    @Test
    void regularConstructorStillTrimsValues() {
        // Guards the OTHER half of the contract: file readers rely on trimming.
        assertEquals("x", rowOf("padded", "  x  ").get("padded"));
    }

    @Test
    void toStringShowsRowIndexAndNormalizedData() {
        assertEquals("DataRow[4] {username=john}", rowOf("UserName", "john", 4).toString());
    }

    @Test
    void getRequiredMessageNamesTheColumnTheRowAndTheAvailableColumns() {
        DataRow row = rowOf("username", "john", 9);
        DataFileException ex = assertThrows(DataFileException.class,
            () -> row.getRequired("password"));
        assertTrue(ex.getMessage().contains("'password'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("row 9"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[username]"), ex.getMessage());
    }

    @Test
    void toMapKeepsColumnOrderAndNormalizedKeys() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("Zeta", "1");
        data.put("Alpha", "2");
        assertEquals("[zeta, alpha]", new DataRow(data, 1).toMap().keySet().toString());
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void keyNormalizationIgnoresTheJvmDefaultLocale() {
        // In a Turkish locale "ID".toLowerCase() would give a dotless i and the lookup would miss.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("42", rowOf("ID", "42").get("id"));
            assertTrue(rowOf("ID", "42").has("Id"));
        } finally {
            Locale.setDefault(original);
        }
    }

    private static DataRow rowOf(String key, String value) {
        return rowOf(key, value, 1);
    }

    private static DataRow rowOf(String key, String value, int rowIndex) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(key, value);
        return new DataRow(data, rowIndex);
    }
}
