package com.automation.core.report.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfDocumentTest {

    @Test
    void helveticaWidthsMatchTheAdobeMetrics() {
        // H=722 e=556 l=222 l=222 o=556  -> 2278/1000 em
        assertEquals(22.78f, PdfDocument.width(PdfDocument.Font.REGULAR, 10, "Hello"), 0.001f);
        // Bold: H=722 i=278 -> 1000/1000 em
        assertEquals(10.0f, PdfDocument.width(PdfDocument.Font.BOLD, 10, "Hi"), 0.001f);
    }

    @Test
    void courierIsMonospaced() {
        assertEquals(24.0f, PdfDocument.width(PdfDocument.Font.MONO, 10, "abcd"), 0.001f);
    }

    @Test
    void encodingMapsWinAnsiAndReplacesTheRest() {
        byte[] bytes = PdfDocument.encode("a\u201cb\u201d\u2014\u20ac\u0939\uD83D\uDE00\t\u0001");
        assertEquals(0x93, bytes[1] & 0xFF, "left double quote");
        assertEquals(0x94, bytes[3] & 0xFF, "right double quote");
        assertEquals(0x97, bytes[4] & 0xFF, "em dash");
        assertEquals(0x80, bytes[5] & 0xFF, "euro");
        assertEquals('?', bytes[6], "Devanagari is outside WinAnsi");
        assertEquals('?', bytes[7], "an emoji is ONE replacement, not two");
        assertEquals(' ', bytes[8], "tab");
        assertEquals(' ', bytes[9], "control character");
        assertEquals(10, bytes.length);
    }
}
