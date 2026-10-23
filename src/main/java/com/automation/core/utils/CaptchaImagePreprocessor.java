package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Image pre-processing stage of the CAPTCHA pipeline, split out of
 * {@link CaptchaSolver}: colour/chroma isolation, contrast normalisation,
 * median denoise, Otsu binarisation, line/strike-through removal and
 * deskew, plus the connected-component finder that the solver's
 * segmentation also builds on.
 *
 * Package-private on purpose: {@link CaptchaSolver} is the public entry
 * point. Behaviour is unchanged from when this code lived in that class;
 * the same {@code captcha.preprocessing.*} config keys apply.
 */
final class CaptchaImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(CaptchaImagePreprocessor.class);

    /**
     * Grayscale + threshold pre-processing for better OCR accuracy.
     * Entry point of this class; everything else below is a pipeline stage.
     */
    BufferedImage preprocess(File imageFile) throws IOException {
        BufferedImage original = ImageIO.read(imageFile);
        if (original == null) {
            // ImageIO.read returns null (not an exception) for a file it has no decoder for -
            // an empty/truncated screenshot, an HTML error page saved as .png, etc. Fail with a
            // clear message instead of a bare NullPointerException on original.getWidth() below.
            throw new IOException("[CaptchaSolver] CAPTCHA image could not be decoded (unsupported, empty or corrupt file): "
                + imageFile);
        }

        // 1. Upscale x2 (Tesseract performs better on larger images)
        int newW = original.getWidth()  * 2;
        int newH = original.getHeight() * 2;
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();

        // 1b. Strip neutral-gray/black decorative noise (strike line, border
        //     box) from the still-in-color image by chroma, BEFORE grayscale
        //     conversion below throws that color information away for good.
        //     See the chromaFilterEnabled field javadoc for why this exists
        //     and why it's safe to run unconditionally (no-ops on a plain
        //     black-text CAPTCHA). Must run before step 2, not after — this
        //     is the whole point: grayscale luminance is exactly what made
        //     this site's blue text and gray noise indistinguishable.
        BufferedImage chromaFiltered = chromaFilterEnabled ? isolateChromaticInk(scaled) : scaled;

        // 2. Convert to grayscale
        BufferedImage gray = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.drawImage(chromaFiltered, 0, 0, null);
        gGray.dispose();

        // 2b. Stretch contrast to the full 0-255 range before anything else.
        //     A washed-out/low-contrast CAPTCHA (light-grey text on a
        //     near-white background, common on some generators to defeat
        //     naive fixed thresholds even before Otsu) otherwise starts the
        //     rest of the pipeline from a compressed grey range that Otsu
        //     has to work harder to split cleanly. This is a no-op (or very
        //     close to it) on an already high-contrast image.
        BufferedImage contrasted = normalizeContrast(gray);

        // 3. Denoise (3x3 median filter) BEFORE thresholding. CAPTCHA
        //    generators commonly add background speckle/dots and thin
        //    crossing lines specifically to defeat OCR; if those survive
        //    into the binarized image, Tesseract reads them as part of a
        //    character stroke and misreads that one glyph while the rest of
        //    the string (in cleaner regions of the image) comes out fine —
        //    which matches "most characters right, a few wrong".
        BufferedImage denoised = medianFilter(contrasted);

        // 4. Binarize with Otsu's method instead of a fixed 128 cutoff. A
        //    hardcoded threshold assumes the CAPTCHA is already pure
        //    black-text-on-white-background; anti-aliased edges, colored
        //    text, or a tinted/gradient background (all common on real
        //    CAPTCHA generators, incl. the india.ai one) shift where "ink"
        //    vs "background" actually falls, so a fixed cutoff binarizes
        //    part of some characters into the background and turns them
        //    into a different, wrong character. Otsu picks the cutoff from
        //    the image's own histogram instead of assuming one.
        int threshold = otsuThreshold(denoised);
        BufferedImage binarized = denoised;
        for (int y = 0; y < binarized.getHeight(); y++) {
            for (int x = 0; x < binarized.getWidth(); x++) {
                int pixel = binarized.getRGB(x, y) & 0xFF;
                binarized.setRGB(x, y, pixel < threshold ? 0x000000 : 0xFFFFFF);
            }
        }

        // 5. Erase a decorative strike-through line that runs straight
        //    across the text itself, crossing through character strokes
        //    rather than just the gaps between them. This is the case
        //    removeLongThinLines() below (component-shape based) cannot
        //    catch — see its own javadoc's "Honest limitation" — because
        //    once the line merges into a character's connected component,
        //    the merged blob is no longer thin/line-shaped as a whole.
        //    removeDiagonalLineThroughText() instead fits a straight line
        //    directly to the pixel data (a lightweight Hough transform)
        //    and erases only the pixels that actually lie on it.
        BufferedImage strikeFree = lineThroughTextRemovalEnabled
            ? removeDiagonalLineThroughText(binarized) : binarized;

        // 5b. Erase long, thin decorative strike-through/underline lines some
        //    CAPTCHA generators draw across (or near) the text specifically
        //    to defeat OCR. These are detected as their own connected
        //    component (only when they don't actually touch a character
        //    stroke — the case above already handles the case where they
        //    do — see removeLongThinLines() javadoc for the rest of its
        //    honest limitation).
        BufferedImage lineFree = lineRemovalEnabled ? removeLongThinLines(strikeFree) : strikeFree;

        // 6. Correct rotation/skew. A CAPTCHA strip that's rendered at a
        //    slight angle (a common distortion) makes every downstream
        //    step worse: Otsu still binarizes fine, but per-character
        //    segmentation's bounding boxes get slanted and start
        //    overlapping each other horizontally, and whole-string OCR's
        //    baseline assumption gets violated. deskew() searches a small
        //    angle range and keeps whichever rotation makes the image's
        //    row-by-row ink profile most sharply defined (see its javadoc).
        BufferedImage deskewed = deskewEnabled ? deskew(lineFree) : lineFree;

        return deskewed;
    }

    /**
     * See the chromaFilterEnabled field javadoc for the full rationale.
     * Classifies every pixel of the still-in-color, already-2x-upscaled
     * screenshot by chroma (max(R,G,B) - min(R,G,B)) rather than brightness:
     * any pixel below chromaFilterThreshold — a neutral gray/black/white
     * pixel, which covers this site's noise line, its border box, and the
     * plain white background all at once — is forced to pure white. Pixels
     * at or above the threshold (real colored ink) are left completely
     * unchanged, so grayscale conversion and everything downstream of it
     * still sees the text's own original anti-aliasing.
     *
     * Returns the image UNCHANGED (not partially filtered) if fewer than
     * chromaFilterMinInkPixels pixels clear the threshold — a plain black-
     * or gray-text CAPTCHA has essentially zero chromatic pixels by this
     * definition, and applying the filter anyway would erase the text
     * itself along with the noise. This check is what makes the filter
     * strictly additive: it only ever activates for CAPTCHAs that actually
     * have colored text to isolate.
     */
    private BufferedImage isolateChromaticInk(BufferedImage scaledColor) {
        int w = scaledColor.getWidth();
        int h = scaledColor.getHeight();

        int inkPixelCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = scaledColor.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (chroma >= chromaFilterThreshold) {
                    inkPixelCount++;
                }
            }
        }
        if (inkPixelCount < chromaFilterMinInkPixels) {
            log.debug("Chroma filter: only {} chromatic pixel(s) found (below floor of {}) — "
                    + "treating this CAPTCHA as plain black/gray text and skipping the filter.",
                inkPixelCount, chromaFilterMinInkPixels);
            return scaledColor;
        }

        BufferedImage out = deepCopy(scaledColor);
        int erased = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = out.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (chroma < chromaFilterThreshold) {
                    out.setRGB(x, y, 0xFFFFFF);
                    erased++;
                }
            }
        }
        log.debug("Chroma filter: {} chromatic ink pixel(s) kept, {} neutral pixel(s) erased "
                + "(line/border/background) before grayscale conversion.",
            inkPixelCount, erased);
        return out;
    }

    /**
     * Linear min-max contrast stretch on a grayscale image: the darkest
     * pixel present becomes pure black (0), the lightest becomes pure white
     * (255), and everything else is scaled proportionally in between. Flat
     * (single-value) images are returned unchanged since there's nothing to
     * stretch.
     */
    private BufferedImage normalizeContrast(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        int min = 255;
        int max = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int v = gray.getRGB(x, y) & 0xFF;
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
        }
        if (max <= min) {
            return gray;
        }
        double scale = 255.0 / (max - min);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int v = gray.getRGB(x, y) & 0xFF;
                int nv = (int) Math.round((v - min) * scale);
                nv = Math.max(0, Math.min(255, nv));
                out.setRGB(x, y, nv * 0x010101);
            }
        }
        return out;
    }

    /**
     * Erases connected components that look like a decorative straight line
     * rather than a character: they span at least 55% of the image's width
     * AND are thin relative to that length (height <= ~18% of their width).
     * Real characters — even a wide cursive "w" or a thin "l" — don't
     * satisfy both conditions at once for a typical multi-character CAPTCHA
     * strip width.
     *
     * Honest limitation: a line that actually crosses THROUGH a character
     * stroke merges with it into one connected component (by definition of
     * 8-connectivity) and is no longer thin/line-shaped as a whole, so this
     * won't catch that case — which is also the most damaging case for OCR.
     * This still reliably removes lines that run through the gaps between
     * characters or along the image margins, which is a real, common
     * CAPTCHA style.
     */
    private BufferedImage removeLongThinLines(BufferedImage binary) {
        List<Segment> comps = findConnectedComponents(binary);
        if (comps.isEmpty()) {
            return binary;
        }
        int w = binary.getWidth();
        BufferedImage out = deepCopy(binary);
        for (Segment s : comps) {
            boolean spansWidth = s.width() >= w * 0.55;
            boolean thin = s.height() <= Math.max(3, (int) (s.width() * 0.18));
            if (spansWidth && thin) {
                eraseRegion(out, s);
                log.debug("Line-removal: erased a {}x{}px component spanning most of the image width — "
                        + "treated as a decorative CAPTCHA strike-through/underline rather than a character.",
                    s.width(), s.height());
            }
        }
        return out;
    }

    private BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private void eraseRegion(BufferedImage img, Segment s) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int x = Math.max(0, s.x0); x < Math.min(w, s.x1); x++) {
            for (int y = Math.max(0, s.y0); y < Math.min(h, s.y1); y++) {
                img.setRGB(x, y, 0xFFFFFF);
            }
        }
    }

    /**
     * Detects and erases a single straight decorative line drawn across —
     * and through — the CAPTCHA text: the "strike-through" style some
     * generators use specifically because it survives per-character
     * segmentation as merged ink rather than a separate, easily-dropped
     * connected component (see removeLongThinLines()'s own javadoc for that
     * honest limitation, which this method exists to cover).
     *
     * Approach: a lightweight Hough transform restricted to nearly-full-
     * width lines. Every foreground (ink) pixel votes, for a range of
     * candidate angles, into a (angle, perpendicular-offset) accumulator
     * bucketed to ~1px resolution. A genuine decorative line — long,
     * straight, and much thinner than a character stroke — produces one
     * accumulator cell with a vote count far higher than anything a
     * curved/irregular character outline can produce at the same
     * resolution, so the strongest candidate is checked against two
     * conditions before anything is erased:
     *   1. the voting pixels' x-range must span most of the image width
     *      (captcha.preprocessing.lineThroughText.minWidthRatio, default
     *      0.55) — a short run of collinear pixels inside one character
     *      (e.g. the crossbar of a "7" or the stem of a "l") can otherwise
     *      register a strong local peak too, but never spans the whole
     *      strip;
     *   2. the peak's own perpendicular thickness (how many adjacent
     *      offset buckets also carry a large fraction of the peak's votes)
     *      must stay within
     *      captcha.preprocessing.lineThroughText.maxThicknessPx (default
     *      5, at this method's already-2x-upscaled-image scale) — a real
     *      stroke run this long would also be many pixels thick, not a
     *      hairline.
     * If both hold, every ink pixel within maxThicknessPx/2 of the fitted
     * line is erased (set to background) — including the handful of
     * character pixels that happen to sit exactly on the line. That's an
     * acceptable trade: Tesseract tolerates a thin gap in a stroke far
     * better than a stray diagonal line running through the whole glyph.
     *
     * A CAPTCHA with no such line simply produces no peak that clears both
     * conditions, so this is a no-op on the common case.
     */
    private BufferedImage removeDiagonalLineThroughText(BufferedImage binary) {
        int w = binary.getWidth();
        int h = binary.getHeight();

        List<int[]> inkPixels = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((binary.getRGB(x, y) & 0xFF) == 0) {
                    inkPixels.add(new int[]{x, y});
                }
            }
        }
        if (inkPixels.isEmpty()) {
            return binary;
        }

        double bestScore = -1;
        double bestAngleRad = 0;
        int bestRhoBin = 0;

        // Candidate angles: a decorative strike line is typically a shallow
        // diagonal — scan a broad-but-bounded range either side of
        // horizontal (near-vertical lines would barely cross more than one
        // character and aren't the pattern this targets).
        for (double angleDeg = -60; angleDeg <= 60; angleDeg += 1.0) {
            double rad = Math.toRadians(angleDeg);
            double cosT = Math.cos(rad);
            double sinT = Math.sin(rad);

            Map<Integer, List<int[]>> byRho = new java.util.HashMap<>();
            for (int[] p : inkPixels) {
                int rhoBin = (int) Math.round(p[0] * cosT + p[1] * sinT);
                byRho.computeIfAbsent(rhoBin, k -> new ArrayList<>()).add(p);
            }

            for (Map.Entry<Integer, List<int[]>> e : byRho.entrySet()) {
                List<int[]> pts = e.getValue();
                if (pts.size() < 2) {
                    continue;
                }
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                for (int[] p : pts) {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                }
                double xSpanRatio = (maxX - minX) / (double) w;
                if (xSpanRatio < lineThroughTextMinWidthRatio) {
                    continue;
                }
                double score = pts.size() * xSpanRatio;
                if (score > bestScore) {
                    bestScore = score;
                    bestAngleRad = rad;
                    bestRhoBin = e.getKey();
                }
            }
        }

        if (bestScore < 0) {
            // No candidate line spanned enough of the image width at any
            // angle — nothing to erase.
            return binary;
        }

        // Measure the winning (angle, rho) peak's perpendicular thickness by
        // checking how many adjacent rho bins also carry a substantial
        // share of the peak's own vote count.
        double cosT = Math.cos(bestAngleRad);
        double sinT = Math.sin(bestAngleRad);
        Map<Integer, Integer> rhoCounts = new java.util.HashMap<>();
        for (int[] p : inkPixels) {
            int rhoBin = (int) Math.round(p[0] * cosT + p[1] * sinT);
            rhoCounts.merge(rhoBin, 1, Integer::sum);
        }
        int peakCount = rhoCounts.getOrDefault(bestRhoBin, 0);
        int thickness = 1;
        for (int offset = 1; offset <= lineThroughTextMaxThicknessPx; offset++) {
            int lo = rhoCounts.getOrDefault(bestRhoBin - offset, 0);
            int hi = rhoCounts.getOrDefault(bestRhoBin + offset, 0);
            if (lo > peakCount * 0.3 || hi > peakCount * 0.3) {
                thickness = offset + 1;
            }
        }
        if (thickness > lineThroughTextMaxThicknessPx) {
            // Too thick to be a hairline decorative line — more likely a
            // dense run of character ink that happened to align at this
            // angle (e.g. several stems in a row). Leave the image
            // untouched rather than risk gutting real characters.
            return binary;
        }

        log.debug("Line-through-text removal: erasing a ~{}deg line (thickness {}px, {} votes) "
                + "crossing the CAPTCHA text.",
            Math.round(Math.toDegrees(bestAngleRad)), thickness, peakCount);

        BufferedImage out = deepCopy(binary);
        double band = thickness / 2.0 + 0.5;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((out.getRGB(x, y) & 0xFF) != 0) {
                    continue;
                }
                double rho = x * cosT + y * sinT;
                if (Math.abs(rho - bestRhoBin) <= band) {
                    out.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
        return out;
    }

    /**
     * Searches rotation angles from -12 to +12 degrees (1-degree steps) and
     * keeps whichever produces the highest variance in the image's
     * row-by-row ink-pixel-count profile — the standard projection-profile
     * deskew heuristic: a well-aligned line of text has ink tightly
     * concentrated in a narrow horizontal band (high row-to-row variance:
     * mostly-empty rows above/below a dense text band), while a skewed one
     * smears ink more evenly across many rows (lower variance). Angles
     * within 0.5 degrees of level are treated as "already straight" and
     * skipped, since rotating (and the resample it requires) has a real
     * cost in edge sharpness for no benefit at that point.
     */
    private BufferedImage deskew(BufferedImage binary) {
        double bestAngle = 0.0;
        double bestScore = -1.0;
        for (double angle = -12.0; angle <= 12.0; angle += 1.0) {
            BufferedImage rotated = rotate(binary, angle);
            double score = rowVarianceScore(rotated);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = angle;
            }
        }
        if (Math.abs(bestAngle) < 0.5) {
            return binary;
        }
        log.debug("Deskew: rotating CAPTCHA image by {} degrees to straighten it before OCR/segmentation", bestAngle);
        return rebinarize(rotate(binary, bestAngle));
    }

    private double rowVarianceScore(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] rowInk = new int[h];
        for (int y = 0; y < h; y++) {
            int count = 0;
            for (int x = 0; x < w; x++) {
                if (isInk(img, x, y)) {
                    count++;
                }
            }
            rowInk[y] = count;
        }
        double mean = 0;
        for (int v : rowInk) {
            mean += v;
        }
        mean /= h;
        double variance = 0;
        for (int v : rowInk) {
            variance += (v - mean) * (v - mean);
        }
        return variance / h;
    }

    private BufferedImage rotate(BufferedImage src, double angleDegrees) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        AffineTransform tx = new AffineTransform();
        tx.translate(w / 2.0, h / 2.0);
        tx.rotate(Math.toRadians(angleDegrees));
        tx.translate(-w / 2.0, -h / 2.0);
        g.setTransform(tx);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Re-applies a plain 128 cutoff after rotation to clean up the mild anti-aliasing rotation introduces along edges. */
    private BufferedImage rebinarize(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int v = img.getRGB(x, y) & 0xFF;
                img.setRGB(x, y, v < 128 ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    /**
     * 3x3 median filter on a grayscale image — replaces each pixel with the
     * median of its 3x3 neighbourhood. Cheap, effective removal of the
     * isolated-dot/speckle noise CAPTCHA generators add, without blurring
     * character edges the way a mean/Gaussian blur would.
     */
    private BufferedImage medianFilter(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        int[] window = new int[9];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = Math.min(Math.max(x + dx, 0), w - 1);
                        int ny = Math.min(Math.max(y + dy, 0), h - 1);
                        window[n++] = gray.getRGB(nx, ny) & 0xFF;
                    }
                }
                java.util.Arrays.sort(window);
                out.setRGB(x, y, window[4] * 0x010101); // median, replicated to R=G=B
            }
        }
        return out;
    }

    /**
     * Otsu's method: picks the grayscale threshold that best separates a
     * bimodal histogram (ink vs. background) by maximizing between-class
     * variance. Standard, well-tested algorithm — no external dependency
     * needed, just a histogram pass plus one linear scan over 256 buckets.
     */
    private int otsuThreshold(BufferedImage gray) {
        int[] histogram = new int[256];
        int w = gray.getWidth();
        int h = gray.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                histogram[gray.getRGB(x, y) & 0xFF]++;
            }
        }

        int total = w * h;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) {
            sumAll += i * (long) histogram[i];
        }

        double sumBackground = 0;
        int weightBackground = 0;
        double maxVariance = 0;
        int bestThreshold = 128; // safe fallback if the image is uniform

        for (int t = 0; t < 256; t++) {
            weightBackground += histogram[t];
            if (weightBackground == 0) {
                continue;
            }

            int weightForeground = total - weightBackground;
            if (weightForeground == 0) {
                break;
            }

            sumBackground += t * (long) histogram[t];

            double meanBackground = sumBackground / weightBackground;
            double meanForeground = (sumAll - sumBackground) / weightForeground;

            double betweenVariance = (double) weightBackground * weightForeground
                * (meanBackground - meanForeground) * (meanBackground - meanForeground);

            if (betweenVariance > maxVariance) {
                maxVariance = betweenVariance;
                bestThreshold = t;
            }
        }

        return bestThreshold;
    }
    /**
     * 8-connected flood fill over the binarized (pure black/white) image,
     * returning the bounding box of every distinct ink blob — the raw
     * candidate set of "maybe a character" regions before noise filtering
     * or fragment merging.
     */
    static List<Segment> findConnectedComponents(BufferedImage binary) {
        int w = binary.getWidth();
        int h = binary.getHeight();
        boolean[][] visited = new boolean[w][h];
        List<Segment> components = new ArrayList<>();
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        Deque<int[]> stack = new ArrayDeque<>();

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (visited[x][y] || !isInk(binary, x, y)) {
                    visited[x][y] = true;
                    continue;
                }
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                int pixelCount = 0;
                visited[x][y] = true;
                stack.push(new int[]{x, y});
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    pixelCount++;
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                    for (int d = 0; d < 8; d++) {
                        int nx = p[0] + dx[d];
                        int ny = p[1] + dy[d];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && !visited[nx][ny] && isInk(binary, nx, ny)) {
                            visited[nx][ny] = true;
                            stack.push(new int[]{nx, ny});
                        }
                    }
                }
                components.add(new Segment(minX, minY, maxX + 1, maxY + 1, pixelCount));
            }
        }
        return components;
    }

    /** True if this pixel is "ink" (foreground/character stroke) in the binarized black-on-white image. */
    static boolean isInk(BufferedImage binary, int x, int y) {
        return (binary.getRGB(x, y) & 0xFF) < 128;
    }
    /**
     * Axis-aligned bounding box of one connected component (candidate
     * character) found by findConnectedComponents(). x1/y1 are EXCLUSIVE
     * (i.e. this is [x0, x1) x [y0, y1)), matching normal image-processing
     * convention so width()/height() need no +1 correction.
     */
    static final class Segment {
        final int x0;
        final int y0;
        final int x1;
        final int y1;
        // Actual foreground/ink pixel count within this component, as counted
        // by the flood fill in findConnectedComponents() — NOT the same as
        // area(), which is just the bounding box's width*height. The two
        // constructors below reflect that distinction: a real connected
        // component (findConnectedComponents()) knows its true ink count and
        // uses the 5-arg constructor; a synthetic segment built from a grid
        // column or a merge of two known components (segmentByGrid(),
        // mergeFragments()) doesn't have a meaningful separate count, so the
        // 4-arg convenience constructor treats it as fully solid
        // (pixelCount == area(), i.e. density() == 1.0) so it's never
        // mistaken for a hollow border frame by removeBorderFrameComponents().
        final int pixelCount;

        Segment(int x0, int y0, int x1, int y1) {
            this(x0, y0, x1, y1, (x1 - x0) * (y1 - y0));
        }

        Segment(int x0, int y0, int x1, int y1, int pixelCount) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.pixelCount = pixelCount;
        }

        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }

        int area() {
            return width() * height();
        }

        /** Fraction of this component's bounding box that's actually ink (1.0 = fully solid). */
        double density() {
            int a = area();
            return a == 0 ? 0.0 : (double) pixelCount / a;
        }
    }
    /**
     * Double-valued config helper. ConfigReader only ships int/boolean/String
     * variants; kept local to this class rather than widening ConfigReader's
     * public contract for a couple of ratio/confidence knobs only this class
     * uses.
     */
    static double getDoubleConfig(String key, double defaultValue) {
        String value = ConfigReader.get(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    // -----------------------------------------------------------------------
    // Additional preprocessing toggles (see preprocess()). Both default
    // on — they're pure image-processing passes that no-op harmlessly on a
    // CAPTCHA that doesn't need them (an already-straight image won't find a
    // better deskew angle than 0; an image with no long straight decorative
    // line won't have anything for removeLongThinLines() to erase).
    // -----------------------------------------------------------------------
    private final boolean deskewEnabled = ConfigReader.getBoolean("captcha.preprocessing.deskew.enabled", true);
    private final boolean lineRemovalEnabled = ConfigReader.getBoolean("captcha.preprocessing.lineRemoval.enabled", true);

    // captcha.preprocessing.lineThroughText.enabled (default true) — see
    // removeDiagonalLineThroughText() javadoc. Complements lineRemovalEnabled
    // above: that pass only catches a decorative line where it DOESN'T touch
    // any character stroke (so it survives as its own separate connected
    // component); a line drawn straight across the middle of the text —
    // which is the far more damaging, far more common strike-through style —
    // merges into the same connected component as every character it
    // crosses and is invisible to a component-shape check. This pass fits a
    // straight line directly to the pixel data instead and erases only the
    // thin band of pixels that actually lie on it, regardless of what
    // they're touching.
    private final boolean lineThroughTextRemovalEnabled =
        ConfigReader.getBoolean("captcha.preprocessing.lineThroughText.enabled", true);
    private final double lineThroughTextMinWidthRatio =
        getDoubleConfig("captcha.preprocessing.lineThroughText.minWidthRatio", 0.55);
    private final int lineThroughTextMaxThicknessPx =
        ConfigReader.getInt("captcha.preprocessing.lineThroughText.maxThicknessPx", 5);

    // captcha.preprocessing.chromaFilter.enabled (default true) — runs BEFORE
    // grayscale conversion, on the still-in-color screenshot. Fixes a gap the
    // two line-removal passes above can't: they both operate on the already-
    // grayscale/binarized image, but some CAPTCHA generators (SAHMAT's
    // observed here) render the real text in a saturated color — RGB(0,102,204)
    // blue on this site — while the decorative noise line AND the CAPTCHA's
    // own border box use a neutral gray — RGB(80,80,80) here. Standard
    // grayscale luminance (0.299R+0.587G+0.114B) collapses those to nearly
    // identical brightness (the blue -> ~83, the gray -> 80, a 3-point gap),
    // so Otsu thresholding — and everything downstream of it, including both
    // line-removal passes — cannot tell the noise line's pixels apart from
    // the text's own. That's what let a curved noise line survive into the
    // binarized image and get read as a fake extra character, producing a
    // wrong-length ensemble result the segmentation pipeline then rejected as
    // untrustworthy (see resolveViaOcr()), falling all the way back to whole-
    // string OCR misreading a real "n343g" as "mag".
    //
    // Fix: classify every pixel by CHROMA (max channel - min channel) instead
    // of brightness. A neutral pixel (this site's gray line/border, or plain
    // black text on any other site) has chroma near 0 no matter how dark it
    // is; colored text has a real, large chroma gap from both the neutral
    // background and the neutral noise. Any pixel below chromaFilterThreshold
    // is forced to pure white here — erasing the line and border in a single
    // pass, independent of the line's shape, angle, or curve (a strict
    // improvement over the straight-line-only Hough fit above). Pixels at or
    // above the threshold are left untouched, so the rest of the pipeline
    // (grayscale, contrast stretch, median filter, Otsu) still runs exactly
    // as before on them.
    //
    // Safety net: only applied when the image actually contains a plausible
    // amount of chromatic ink (captcha.preprocessing.chromaFilter.minInkPixels,
    // counted on the already-2x-upscaled image). A genuinely black/gray-text
    // CAPTCHA would otherwise have every pixel erased by this filter — in
    // that case isolateChromaticInk() returns the image completely unchanged
    // and the grayscale/Otsu pipeline behaves exactly as it did before this
    // was added, so this can never regress a plain black-text CAPTCHA.
    private final boolean chromaFilterEnabled =
        ConfigReader.getBoolean("captcha.preprocessing.chromaFilter.enabled", true);
    private final int chromaFilterThreshold =
        ConfigReader.getInt("captcha.preprocessing.chromaFilter.threshold", 40);
    private final int chromaFilterMinInkPixels =
        ConfigReader.getInt("captcha.preprocessing.chromaFilter.minInkPixels", 30);

}
