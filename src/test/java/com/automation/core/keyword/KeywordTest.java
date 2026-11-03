package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure enum-lookup logic: how a keyword cell in a script file becomes a {@link Keyword}. */
class KeywordTest {

    @Test
    void lookupIsCaseInsensitive() {
        assertEquals(Keyword.CLICK, Keyword.from("click"));
        assertEquals(Keyword.CLICK, Keyword.from("CLICK"));
        assertEquals(Keyword.CLICK, Keyword.from("ClIcK"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(Keyword.NAVIGATE, Keyword.from("  navigate \t"));
    }

    @Test
    void spacesBetweenWordsAreTreatedAsUnderscores() {
        assertEquals(Keyword.SELECT_BY_TEXT, Keyword.from("select by text"));
        assertEquals(Keyword.VERIFY_URL_CONTAINS, Keyword.from("Verify URL Contains"));
    }

    @Test
    void everyKeywordCanBeWrittenInItsOwnNameAndInLowerCaseWithSpaces() {
        for (Keyword keyword : Keyword.values()) {
            assertEquals(keyword, Keyword.from(keyword.name()), keyword.name());
            String spaced = keyword.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            assertEquals(keyword, Keyword.from(spaced), spaced);
        }
    }

    @Test
    void nullCellIsRejected() {
        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> Keyword.from(null));
        assertTrue(ex.getMessage().contains("Keyword cell is empty"), ex.getMessage());
    }

    @Test
    void blankCellIsRejected() {
        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> Keyword.from("   "));
        assertTrue(ex.getMessage().contains("Keyword cell is empty"), ex.getMessage());
    }

    @Test
    void unknownKeywordNamesTheTypoAndListsTheSupportedOnes() {
        KeywordExecutionException ex = assertThrows(KeywordExecutionException.class,
            () -> Keyword.from("CLIK"));
        assertTrue(ex.getMessage().contains("Unknown keyword: 'CLIK'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Supported:"), ex.getMessage());
        assertTrue(ex.getMessage().contains("CLICK"), ex.getMessage());
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void lookupIgnoresTheJvmDefaultLocale() {
        // In a Turkish locale "click".toUpperCase() would produce a dotted capital I.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(Keyword.CLICK, Keyword.from("click"));
            assertEquals(Keyword.SELECT_BY_INDEX, Keyword.from("select_by_index"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
