package com.automation.core.selfhealing;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import ru.yandex.qatools.ashot.AShot;
import ru.yandex.qatools.ashot.Screenshot;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Perceptual difference-hash (dHash) for a single element's screenshot —
 * the visual counterpart to {@link ElementFingerprint}'s DOM attributes.
 *
 * DOM-similarity scoring in {@link SelfHealingEngine} can only ever compare
 * elements that share a tag, and it has nothing useful to say about an icon
 * button or a div-based "button" with no id/name/stable text — exactly the
 * elements most likely to lose a locator match to a routine markup refactor.
 * A dHash gives a second, independent signal — "does this candidate still
 * *look* like the element we lost?" — that works regardless of tag or
 * attributes, so it can catch drift attribute scoring alone cannot (e.g. a
 * `<button>` that became a `<div role="button">`).
 *
 * dHash rather than a full pixel diff (as {@link
 * com.automation.core.utils.VisualRegressionUtils} uses for whole-page
 * comparisons) because: (1) it's a single 16-hex-char string, cheap enough
 * to persist in locator-repository.json alongside every other fingerprint
 * field without bloating it the way embedding a screenshot per element
 * would, and (2) it tolerates the minor anti-aliasing/sub-pixel differences
 * between two separate screenshots of "the same" element that an exact
 * pixel diff would flag as a mismatch.
 *
 * Deliberately package-private — this is an implementation detail of
 * self-healing, not a general-purpose imaging utility (that's
 * VisualRegressionUtils' job).
 */
final class VisualHasher {

    private static final Logger logger = LoggerFactory.getLogger(VisualHasher.class);

    /** 9 columns x 8 rows -> 8 horizontal-neighbor comparisons per row = 64 bits, fits a long. */
    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;
    private static final int HASH_BITS = (HASH_WIDTH - 1) * HASH_HEIGHT;

    private VisualHasher() {
    }

    /**
     * Screenshots just this element (cropped, via AShot — works against
     * RemoteWebDriver/Grid nodes the same as a local driver) and returns its
     * dHash as a fixed-width hex string, or {@code null} if the element
     * couldn't be captured (e.g. zero-size, off-screen, or a driver that
     * doesn't support screenshots — never lets imaging failures break the
     * caller).
     */
    static String hash(WebDriver driver, WebElement element) {
        try {
            Screenshot shot = new AShot().takeScreenshot(driver, element);
            BufferedImage image = shot.getImage();
            if (image == null || image.getWidth() < 2 || image.getHeight() < 2) {
                return null;
            }
            return dHash(image);
        } catch (Exception e) {
            logger.debug("[SelfHealing] Could not capture visual hash: " + e.getMessage());
            return null;
        }
    }

    private static String dHash(BufferedImage source) {
        // Scaling straight into a TYPE_BYTE_GRAY target both resizes and
        // grayscales in one pass.
        BufferedImage small = new BufferedImage(HASH_WIDTH, HASH_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.drawImage(source, 0, 0, HASH_WIDTH, HASH_HEIGHT, null);
        } finally {
            g.dispose();
        }

        long bits = 0L;
        int bitIndex = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++) {
                int left = small.getRaster().getSample(x, y, 0);
                int right = small.getRaster().getSample(x + 1, y, 0);
                if (left > right) {
                    bits |= (1L << bitIndex);
                }
                bitIndex++;
            }
        }
        return String.format("%016x", bits);
    }

    /**
     * Similarity in [0, 1] between two dHash hex strings via normalized
     * Hamming distance — 1.0 is visually identical (by this coarse a
     * measure), 0.0 is maximally different. Either side being missing
     * (never captured, or capture failed) yields 0.0 — no signal, not a
     * match.
     */
    static double similarity(String hashA, String hashB) {
        if (hashA == null || hashB == null || hashA.isEmpty() || hashB.isEmpty()) {
            return 0.0;
        }
        try {
            long a = Long.parseUnsignedLong(hashA, 16);
            long b = Long.parseUnsignedLong(hashB, 16);
            int distance = Long.bitCount(a ^ b);
            return 1.0 - ((double) distance / HASH_BITS);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
