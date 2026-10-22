package com.automation.core.report.pdf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfEvidenceStoreTest {

    @AfterEach
    void clean() {
        PdfEvidenceStore.clear();
    }

    @Test
    void storesAndReplacesPerTest() {
        String key = PdfEvidenceStore.key("a.B", "m", null);
        PdfEvidenceStore.put(key, new byte[] {1});
        PdfEvidenceStore.put(key, new byte[] {2, 3});
        assertEquals(2, PdfEvidenceStore.get(key).length, "a retry replaces the earlier attempt's image");
        assertEquals(1, PdfEvidenceStore.size());
    }

    @Test
    void emptyInputIsIgnored() {
        PdfEvidenceStore.put("k", new byte[0]);
        PdfEvidenceStore.put("k", null);
        PdfEvidenceStore.put(null, new byte[] {1});
        assertEquals(0, PdfEvidenceStore.size());
        assertNull(PdfEvidenceStore.get("k"));
    }

    @Test
    void keysDifferByParametersButNeverContainThem() {
        String a = PdfEvidenceStore.key("a.B", "login", new Object[] {"alice", "s3cret!"});
        String b = PdfEvidenceStore.key("a.B", "login", new Object[] {"bob", "other"});
        assertFalse(a.equals(b));
        assertFalse(a.contains("s3cret"), a);
        assertEquals(a, PdfEvidenceStore.key("a.B", "login", new Object[] {"alice", "s3cret!"}));
    }

    @Test
    void entryCountIsCapped() {
        for (int i = 0; i < PdfEvidenceStore.MAX_ENTRIES + 20; i++) {
            PdfEvidenceStore.put("k" + i, new byte[] {1});
        }
        assertEquals(PdfEvidenceStore.MAX_ENTRIES, PdfEvidenceStore.size());
        PdfEvidenceStore.put("k0", new byte[] {9, 9});
        assertEquals(2, PdfEvidenceStore.get("k0").length, "an existing entry can still be replaced when full");
    }

    @Test
    void clearForgetsEverything() {
        PdfEvidenceStore.put("k", new byte[] {1});
        PdfEvidenceStore.clear();
        assertEquals(0, PdfEvidenceStore.size());
        assertTrue(PdfEvidenceStore.get("k") == null);
    }
}
