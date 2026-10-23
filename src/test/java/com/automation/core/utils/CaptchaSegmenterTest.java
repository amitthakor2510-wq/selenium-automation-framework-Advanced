package com.automation.core.utils;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Word;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CaptchaSegmenter} with a fake Tesseract (no native
 * OCR library needed). The fake answers per character whitelist, so these
 * tests check the segmentation itself — how many glyphs were found, in what
 * order, and when the segmenter gives up — not Tesseract's recognition.
 */
class CaptchaSegmenterTest {

    private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * Fake Tesseract: for a whitelist containing letters it "reads" the first
     * letter with {@code letterConfidence}; for the digit-only whitelist it
     * reads a digit with a low confidence, so the letter wins.
     */
    private static ITesseract fakeTesseract(float letterConfidence) {
        String[] whitelist = {CHARSET};
        return (ITesseract) Proxy.newProxyInstance(
            ITesseract.class.getClassLoader(),
            new Class<?>[] {ITesseract.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setVariable":
                        if ("tessedit_char_whitelist".equals(args[0])) {
                            whitelist[0] = (String) args[1];
                        }
                        return null;
                    case "getWords":
                        boolean hasLetters = whitelist[0].chars().anyMatch(Character::isLetter);
                        Word word = hasLetters
                            ? new Word("A", letterConfidence, new Rectangle(0, 0, 1, 1))
                            : new Word("7", 5f, new Rectangle(0, 0, 1, 1));
                        return List.of(word);
                    default:
                        return null;
                }
            });
    }

    private static CaptchaSegmenter segmenter(float letterConfidence) {
        return new CaptchaSegmenter(fakeTesseract(letterConfidence), new CaptchaImagePreprocessor(), CHARSET);
    }

    /** White 160x48 image with one solid black 14x28 block per x position — a stand-in for separated glyphs. */
    private static BufferedImage imageWithGlyphsAt(int... xs) {
        BufferedImage img = new BufferedImage(160, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        for (int x : xs) {
            g.fillRect(x, 10, 14, 28);
        }
        g.dispose();
        return img;
    }

    @Test
    void findsOneSegmentPerSeparatedGlyph() {
        CaptchaSegmenter.SegResult result = segmenter(90f).segmentedIdentify(imageWithGlyphsAt(15, 50, 85, 120), 0);

        assertNotNull(result, "four clearly separated glyphs should segment");
        assertEquals(4, result.text.length());
        assertEquals("AAAA", result.text.toUpperCase());
        assertTrue(result.avgConfidence >= 85.0, "average confidence should reflect the fake's 90");
    }

    @Test
    void segmentCountFollowsTheNumberOfGlyphs() {
        CaptchaSegmenter.SegResult three = segmenter(90f).segmentedIdentify(imageWithGlyphsAt(20, 70, 120), 0);
        CaptchaSegmenter.SegResult five = segmenter(90f).segmentedIdentify(imageWithGlyphsAt(8, 38, 68, 98, 128), 0);

        assertNotNull(three);
        assertNotNull(five);
        assertEquals(3, three.text.length());
        assertEquals(5, five.text.length());
    }

    @Test
    void acceptsAMatchingKnownLength() {
        CaptchaSegmenter.SegResult result = segmenter(90f).segmentedIdentify(imageWithGlyphsAt(15, 50, 85, 120), 4);

        assertNotNull(result);
        assertEquals(4, result.text.length());
    }

    @Test
    void returnsNullWhenAnyGlyphIsReadBelowTheConfidenceFloor() {
        // The default floor is 35; the fake reports 5 for every glyph.
        assertNull(segmenter(5f).segmentedIdentify(imageWithGlyphsAt(15, 50, 85, 120), 0));
    }

    @Test
    void returnsNullForABlankImage() {
        assertNull(segmenter(90f).segmentedIdentify(imageWithGlyphsAt(), 0));
    }

    @Test
    void returnsNullWhenTooFewGlyphsAreFound() {
        // Below captcha.segmentation.minChars (default 3).
        assertNull(segmenter(90f).segmentedIdentify(imageWithGlyphsAt(40, 100), 0));
    }
}
