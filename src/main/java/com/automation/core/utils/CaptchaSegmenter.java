package com.automation.core.utils;

import com.automation.core.utils.CaptchaImagePreprocessor.Segment;
import com.automation.core.config.ConfigReader;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.automation.core.utils.CaptchaImagePreprocessor.findConnectedComponents;
import static com.automation.core.utils.CaptchaImagePreprocessor.getDoubleConfig;
import static com.automation.core.utils.CaptchaImagePreprocessor.isInk;

/**
 * Per-character segmentation and single-glyph OCR for {@link CaptchaSolver}.
 *
 * <p>Extracted from CaptchaSolver unchanged in behaviour: connected-component
 * segmentation, noise/border-frame filtering, touching-character splitting,
 * single-character OCR with confusable-pair (0/O, 1/l, 5/S, a/@ ...) shape
 * tiebreaks, and the relative-height case correction. CaptchaSolver keeps
 * element detection, orchestration and typing; it calls
 * {@link #segmentedIdentify} and {@link #applyRelativeCaseCorrectionToVisionAnswer}.
 * All config keys ({@code captcha.segmentation.*}) are unchanged.
 */
final class CaptchaSegmenter {

    private static final Logger log = LoggerFactory.getLogger(CaptchaSegmenter.class);

    private final ITesseract tesseract;
    private final CaptchaImagePreprocessor preprocessor;
    private final String textCaptchaCharset;

    CaptchaSegmenter(ITesseract tesseract, CaptchaImagePreprocessor preprocessor, String textCaptchaCharset) {
        this.tesseract = tesseract;
        this.preprocessor = preprocessor;
        this.textCaptchaCharset = textCaptchaCharset;
    }

    // -----------------------------------------------------------------------
    // Per-character segmentation config (OCR-mode accuracy — see
    // segmentCharacters()/segmentedIdentify() javadoc). This is what actually
    // helps distinguish letters from digits (0/O, 1/I/l, 5/S, 8/B, etc.):
    // whole-string OCR (PSM 7/8) classifies each glyph with its neighbours'
    // strokes bleeding into the recognition window, which is the single
    // biggest cause of that exact kind of confusion in tightly-kerned
    // CAPTCHA fonts. Isolating each character first and reading it alone
    // (PSM 10) removes that interference. Bounds are configurable per site
    // since a very short/long CAPTCHA alphabet differs from site to site.
    private final boolean segmentationEnabled = ConfigReader.getBoolean("captcha.segmentation.enabled", true);
    private final int segMinChars = ConfigReader.getInt("captcha.segmentation.minChars", 3);

    private final int segMaxChars = ConfigReader.getInt("captcha.segmentation.maxChars", 12);

    // How many pixels of white space to pad around each isolated character
    // crop before OCR-ing it alone. Tesseract (like most OCR engines) reads
    // glyphs worse when they're flush against the image edge — a small
    // margin gives PSM 10 the same "breathing room" a full CAPTCHA strip
    // naturally has around its first/last character.
    private final int segPaddingPx = ConfigReader.getInt("captcha.segmentation.paddingPx", 6);

    // Each isolated single-character crop is upscaled by this factor (on top
    // of preprocess()'s own 2x) before OCR — small crops (a single
    // glyph might only be ~15x25px before this) benefit far more from
    // upscaling than a full multi-character strip does, since Tesseract's
    // trained models expect a minimum stroke width in pixels to classify
    // reliably.
    private final int segUpscale = ConfigReader.getInt("captcha.segmentation.upscale", 4);

    // Connected components smaller than (median component area * this
    // ratio) are treated as background speckle/noise rather than a real
    // character fragment and dropped before segmentation runs. Tunable per
    // site: a CAPTCHA generator with heavy background noise may need this
    // raised; one with very small/thin fonts (where a real "l" or "1" is
    // legitimately tiny) may need it lowered.
    private final double segMinComponentAreaRatio = getDoubleConfig("captcha.segmentation.minComponentAreaRatio", 0.12);

    // Minimum Tesseract confidence (0-100) a single isolated character must
    // reach before segmentedIdentify() trusts it. Below this, the whole
    // segmented result is abandoned in favour of whole-string OCR instead of
    // typing a low-confidence guess for one glyph.
    private final double segConfidenceFloor = getDoubleConfig("captcha.segmentation.confidenceFloor", 35.0);

    // Height ratio (this character's height / the tallest anchor character's
    // height in the same CAPTCHA) below which a case-symmetric letter (see
    // CASE_SYMMETRIC_LETTERS/applyRelativeCaseCorrection()) is treated as
    // lower-case rather than upper-case. 0.78 is deliberately conservative —
    // a genuine lowercase x-height glyph (c/o/s/u/v/w/x/z) is typically
    // ~60-70% of a full ascender/digit height in most CAPTCHA fonts, so this
    // sits above that band with margin rather than right on the boundary.
    private final double caseHeightRatioThreshold = getDoubleConfig("captcha.segmentation.caseHeightRatio", 0.78);

    // Some CAPTCHA templates draw a decorative box/rule border around the
    // whole image. A thin rectangular outline is hollow (low ink-fill
    // ratio) but its bounding box spans almost the entire image — the
    // opposite signature of a real character, which is compact but mostly
    // solid. Left alone, findConnectedComponents() picks the border up as
    // one giant "component" that still passes the broad segMinChars/
    // segMaxChars range check, so segmentedIdentify() tries to OCR the
    // border rectangle itself as a glyph and corrupts the whole read.
    // removeBorderFrameComponents() drops any component whose bounding box
    // covers at least borderFrameMinDimRatio of BOTH image dimensions and
    // whose fill density is at or below borderFrameMaxDensity. Real glyphs
    // are always denser than this even in a thin font, and a genuinely
    // full-bleed dense character (unusual, but possible in a very short/
    // large-font CAPTCHA) is protected by the density check.
    //
    // minDimRatio's two axes are NOT symmetric in practice — measured
    // directly against real india.ai CAPTCHA screenshots (text_captcha_
    // 20260820_111822.png / _111842.png), the border's own width ratio was
    // ~0.91 but its height ratio only ~0.74 (the screenshot margin around
    // the CAPTCHA sits between the border and the image edge more on the
    // vertical axis than the horizontal one). The original 0.85 default
    // assumed both axes would look similar and missed this border
    // entirely — hRatio 0.74 < 0.85 — letting it through unremoved even
    // with this whole pass enabled. 0.65 is calibrated with margin under
    // that measured 0.74. maxDensity likewise needed headroom: the SAME
    // border template measured 0.057 density in one of those two real
    // screenshots but 0.151 in the other (a slightly heavier-rendered
    // outline, still the same decorative frame) — the original 0.15
    // default sat just below that second value and missed it too. 0.22
    // keeps comfortable margin above both measured values while staying
    // well under every real character's measured density in the same
    // images (0.45-0.72).
    private final boolean borderFrameRemovalEnabled =
        ConfigReader.getBoolean("captcha.segmentation.borderFrame.removalEnabled", true);
    private final double borderFrameMinDimRatio =
        getDoubleConfig("captcha.segmentation.borderFrame.minDimRatio", 0.65);
    private final double borderFrameMaxDensity =
        getDoubleConfig("captcha.segmentation.borderFrame.maxDensity", 0.22);

    // Some fonts/generators render adjacent characters touching or
    // overlapping by a pixel or two, so findConnectedComponents() merges
    // them into a single connected component instead of two — and on a
    // tightly-kerned CAPTCHA font, THREE OR MORE characters can fuse into
    // one blob this way, not just two. A per-column ink-count profile
    // across such a component still shows a real valley (a local dip in
    // ink) at each true boundary between glyphs — splitTouchingCharacters()
    // looks for that valley in any component that's suspiciously wide
    // relative to one real character's width in this image, and splits
    // there, then re-checks EACH resulting half the same way (so a 4-glyph
    // blob gets split down to 4, not left as two still-merged pairs after
    // a single one-shot split). A candidate split is only taken if the
    // valley's ink count is no more than touchingCharValleyMaxRatio of the
    // component's own peak column in that same width-check window — a
    // shallow dip (e.g. the waist of a single "m"/"w") is left alone
    // rather than risk shredding one wide character into garbage
    // fragments, and that same per-attempt confidence check is what keeps
    // the recursion from over-splitting a genuinely wide single glyph.
    private final boolean touchingCharSplitEnabled =
        ConfigReader.getBoolean("captcha.segmentation.touchingChars.splitEnabled", true);
    private final double touchingCharWidthRatio =
        getDoubleConfig("captcha.segmentation.touchingChars.widthRatio", 1.6);
    private final double touchingCharValleyMaxRatio =
        getDoubleConfig("captcha.segmentation.touchingChars.valleyMaxRatio", 0.35);

    // The three thresholds above are tuned to be conservative by default —
    // safe against shredding one wide glyph into garbage on a "normal" font.
    // But some fonts (heavily stylized/cursive CAPTCHA generators, e.g. the
    // real india.ai samples with a flourished "g") fuse characters tightly
    // enough, or with a shallow-enough valley, that the conservative pass
    // under-splits: it correctly avoids splitting where it isn't sure, but
    // "isn't sure" here means "the boundary was harder to find", not "there
    // is no boundary". Rather than hand-tune per-site overrides for every
    // such font (fragile — a config value picked to fit one CAPTCHA sample
    // can just as easily be wrong for the next one from the same site), when
    // knownExpectedLength is available we know for certain we're one or more
    // characters short and can afford to retry with progressively relaxed
    // thresholds — see splitTouchingCharactersAdaptive() — stopping the
    // moment the exact expected count is reached rather than relaxing
    // further than necessary.
    private final boolean touchingCharAdaptiveRelaxEnabled =
        ConfigReader.getBoolean("captcha.segmentation.touchingChars.adaptiveRelax.enabled", true);
    private final int touchingCharMaxRelaxSteps =
        ConfigReader.getInt("captcha.segmentation.touchingChars.adaptiveRelax.maxSteps", 3);
    private final double touchingCharWidthRatioStep =
        getDoubleConfig("captcha.segmentation.touchingChars.adaptiveRelax.widthRatioStep", 0.15);
    private final double touchingCharValleyMaxRatioStep =
        getDoubleConfig("captcha.segmentation.touchingChars.adaptiveRelax.valleyMaxRatioStep", 0.10);
    private final double touchingCharMinHalfRatioStep =
        getDoubleConfig("captcha.segmentation.touchingChars.adaptiveRelax.minHalfRatioStep", 0.05);
    // Floors so relaxation can't run away into accepting noise as a split:
    // even at the most relaxed step, a "half" narrower than 22% of a real
    // character's width, or a valley deeper than 65% of the peak column,
    // isn't a plausible glyph boundary on any font this framework targets.
    private static final double MIN_SPLIT_HALF_RATIO_FLOOR = 0.22;
    private static final double VALLEY_MAX_RATIO_CEILING = 0.65;

    // Character pairs that classical OCR (and even a hasty human glance)
    // confuses most often, used as a lightweight geometric tiebreaker in
    // resolveConfusableByShape() — see that method's javadoc. Deliberately
    // NOT exhaustive: only pairs with a genuinely usable shape signal are
    // handled there (0/O has no reliable generic rule across fonts and is
    // intentionally left to OCR confidence alone).
    // Deliberately symmetric (every partner listed also lists the original
    // char back) so the tiebreak in identifySingleCharacter() fires
    // regardless of which of the two candidates OCR happened to rank first.
    private static final Map<Character, char[]> CONFUSABLE_PAIRS = Map.ofEntries(
        Map.entry('0', new char[]{'O', 'o', 'D'}),
        Map.entry('O', new char[]{'0'}),
        Map.entry('o', new char[]{'0'}),
        Map.entry('D', new char[]{'0'}),
        // '1'/'I'/'l'/'i' are the classic confusable set, and a distorted/
        // wavy CAPTCHA font routinely also renders '!' close enough to a
        // narrow vertical stroke that OCR (and an unconstrained vision
        // prompt) misreads one for the other — added '!' to all of them.
        Map.entry('1', new char[]{'I', 'l', 'i', '!'}),
        Map.entry('I', new char[]{'1', 'l', '!'}),
        Map.entry('l', new char[]{'1', 'I', 'i', '!'}),
        Map.entry('i', new char[]{'1', 'l'}),
        Map.entry('!', new char[]{'1', 'I', 'l'}),
        Map.entry('2', new char[]{'Z'}),
        Map.entry('Z', new char[]{'2'}),
        // '$' is a near-universal misread of 'S'/'s' with a stray stroke
        // through it (or vice versa: a genuine '$' losing its vertical bar
        // to anti-aliasing and getting read as 'S').
        Map.entry('5', new char[]{'S', 's', '$'}),
        Map.entry('S', new char[]{'5', '$'}),
        Map.entry('s', new char[]{'5', '$'}),
        Map.entry('$', new char[]{'S', 's', '5'}),
        Map.entry('6', new char[]{'G', 'b'}),
        Map.entry('G', new char[]{'6'}),
        Map.entry('b', new char[]{'6'}),
        Map.entry('8', new char[]{'B'}),
        Map.entry('B', new char[]{'8'}),
        Map.entry('9', new char[]{'g', 'q'}),
        Map.entry('g', new char[]{'9'}),
        Map.entry('q', new char[]{'9'}),
        Map.entry('u', new char[]{'v', 'U', 'V'}),
        Map.entry('v', new char[]{'u'}),
        Map.entry('U', new char[]{'v'}),
        Map.entry('V', new char[]{'u'}),
// --- NEW: Added to fix m/n, h/n, and c/e confusions ---
        Map.entry('m', new char[]{'n'}),
        Map.entry('n', new char[]{'m', 'h'}),
        Map.entry('h', new char[]{'n'}),
        Map.entry('c', new char[]{'e'}),
        // 'a'/'A' -> '@' is the exact "letter misread as a special
        // character" failure this fix targets: a single-storey 'a' in a
        // rounded/distorted CAPTCHA font is a near-enclosed loop with a
        // short right-hand stroke — visually one flourish away from '@'.
        // See resolveConfusableByShape() for the geometric tiebreak.
        Map.entry('a', new char[]{'@', 'f'}),
        Map.entry('A', new char[]{'@'}),
        Map.entry('@', new char[]{'a', 'A'}),
        Map.entry('e', new char[]{'c'}),
        // 'f' vs 'a': seen for real on SAHMAT's CAPTCHA — this site draws a
        // thin decorative strike-through/noise line across the whole image,
        // and when that line happens to cross an 'f' near its ascender it
        // visually closes the shape into something that reads as a bowl,
        // i.e. an 'a'. Genuine 'f' has a tall ascender rising above the
        // other characters plus a horizontal crossbar and no closed loop at
        // the baseline; 'a' is a fully closed bowl the same height as
        // neighbouring lowercase letters with no ascender at all.
        Map.entry('f', new char[]{'a'})
    );



    /** A single OCR candidate for one isolated character, with its confidence (0-100, Tesseract scale). */
    static final class CharGuess {
        final String text;
        final double confidence;

        CharGuess(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    /** Result of a full segmented (per-character) read of a CAPTCHA image. */
    static final class SegResult {
        final String text;
        final double avgConfidence;

        SegResult(String text, double avgConfidence) {
            this.text = text;
            this.avgConfidence = avgConfidence;
        }
    }

    /**
     * Attempts a full per-character read of the CAPTCHA: split the image
     * into candidate character blobs, drop noise speckles, merge fragments
     * that belong to the same glyph, then OCR each surviving blob completely
     * alone (identifySingleCharacter()). Returns null (never throws to the
     * caller) whenever segmentation isn't trustworthy for this image —
     * caller falls back to whole-string OCR in that case:
     *   - captcha.segmentation.enabled=false
     *   - component count outside [captcha.segmentation.minChars, .maxChars]
     *     after noise-filtering/merging (glyphs are touching/overlapping too
     *     much to have separated cleanly, or the image is mostly noise)
     *   - knownExpectedLength is set (from the answer field's maxlength, or
     *     captcha.expected.length) and neither connected-component nor grid
     *     segmentation could hit that exact count — see the note below on
     *     why this returns null instead of falling through to a wrong-length
     *     answer
     *   - any single surviving character can't be classified with at least
     *     captcha.segmentation.confidenceFloor confidence
     */
    SegResult segmentedIdentify(BufferedImage processed, int knownExpectedLength) {
        if (!segmentationEnabled) {
            return null;
        }
        try {
            List<Segment> raw = findConnectedComponents(processed);
            List<Segment> noBorder = removeBorderFrameComponents(raw, processed.getWidth(), processed.getHeight());
            List<Segment> denoised = filterNoiseComponents(noBorder);
            // mergeFragments() runs BEFORE splitTouchingCharacters(), not
            // after: a glyph that's been broken into pieces (a dot
            // separated from its stem, a stroke split by denoising) needs
            // to be whole again before deciding whether ITS resulting
            // width represents one character or several touching ones —
            // splitting first would let same-glyph fragments masquerade as
            // separate "characters" and get counted/processed as if they
            // already were.
            List<Segment> wholeGlyphs = mergeFragments(denoised);
            // Adaptive: only ever relaxes past the conservative default
            // thresholds when knownExpectedLength proves we're short by a
            // known amount — see splitTouchingCharactersAdaptive() javadoc.
            // With no known length it behaves identically to the old
            // single-pass splitTouchingCharacters() call.
            List<Segment> merged = splitTouchingCharactersAdaptive(wholeGlyphs, processed, knownExpectedLength);
            merged.sort(Comparator.comparingInt(s -> s.x0));

            // If we know the CAPTCHA's exact length (see resolveExpectedLength()
            // — the answer field's own maxlength is the common source), a
            // connected-component count that disagrees with it is itself
            // evidence of a bad read: extra noise picked up as a fake glyph,
            // or one glyph accidentally split into two, both of which still
            // pass the broad segMinChars/segMaxChars sanity check below and
            // would otherwise be typed as a too-long/too-short answer. Try
            // grid segmentation (which segments by count, not by shape, so
            // it isn't fooled by the same noise/split) instead — and if THAT
            // still can't produce the right count either, this specific
            // image just isn't segmentable reliably: return null and let the
            // caller fall back to whole-string OCR rather than silently
            // typing an answer we already know is the wrong length.
            if (knownExpectedLength > 0 && merged.size() != knownExpectedLength) {
                log.debug("Connected components found {} segments, but the CAPTCHA's known length is {} "
                        + "— falling back to grid segmentation.",
                    merged.size(), knownExpectedLength);
                SegResult gridResult = segmentByGrid(processed, knownExpectedLength);
                if (gridResult != null) {
                    return gridResult;
                }
                log.debug("Grid segmentation also couldn't produce {} characters for this image — "
                        + "abandoning the segmented result rather than typing a known-wrong-length answer "
                        + "(whole-string OCR will be used instead).",
                    knownExpectedLength);
                return null;
            }

            if (merged.size() < segMinChars || merged.size() > segMaxChars) {
                log.debug("Segmentation found {} character component(s), outside the configured range.", merged.size());
                return null;
            }

            // ... (keep the rest of the existing for-loop exactly as it is) ...

            StringBuilder text = new StringBuilder();
            List<Double> confidences = new ArrayList<>();
            List<CharGuess> rawGuesses = new ArrayList<>();
            for (Segment seg : merged) {
                CharGuess guess = identifySingleCharacter(processed, seg);
                if (guess == null) {
                    log.debug("Segmentation: one character segment could not be classified with enough "
                        + "confidence — abandoning the segmented result for this image (whole-string OCR "
                        + "will be used instead).");
                    return null;
                }
                rawGuesses.add(guess);
            }

            // Relative-height case correction (see applyRelativeCaseCorrection()
            // javadoc): must run as its own pass over ALL segments, after every
            // segment's raw guess is known, because the reference (full-glyph)
            // height is only meaningful once we've seen every character in
            // this CAPTCHA — it can't be computed one character at a time.
            int referenceHeight = 0;
            for (int i = 0; i < merged.size(); i++) {
                char c = Character.toUpperCase(rawGuesses.get(i).text.charAt(0));
                if (CASE_SYMMETRIC_LETTERS.indexOf(c) < 0) {
                    referenceHeight = Math.max(referenceHeight, merged.get(i).height());
                }
            }

            for (int i = 0; i < merged.size(); i++) {
                CharGuess guess = applyRelativeCaseCorrection(rawGuesses.get(i), merged.get(i), referenceHeight);
                text.append(guess.text);
                confidences.add(guess.confidence);
            }

            double avg = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            return new SegResult(text.toString(), avg);

        } catch (Exception e) {
            log.warn("⚠ Segmented character-by-character OCR failed ({}) — falling back to whole-string OCR",
                e.getMessage());
            return null;
        }
    }

    // Letters whose upper- and lower-case glyphs are the SAME shape, just a
    // different size (a lowercase "z" is a scaled-down "Z", not a
    // differently-shaped glyph the way e.g. "a"/"A" or "e"/"E" are). This is
    // the actual reason isolated per-character OCR mis-cases these specific
    // letters: identifySingleCharacter() crops each glyph out on its own and
    // upscales it to a fixed size (segUpscale) before classifying it, which
    // throws away the one signal that distinguishes case for an
    // identically-shaped pair — how tall it was relative to the other
    // characters in the same CAPTCHA. Digits and shape-distinct letters
    // (case doesn't change their shape ambiguously) are unaffected and are
    // deliberately excluded here.
    private static final String CASE_SYMMETRIC_LETTERS = "COSUVWXZ";

    /**
     * Overrides a segmented character's case using its height relative to
     * the tallest non-case-symmetric ("anchor") character in the same
     * CAPTCHA, for exactly the letters in CASE_SYMMETRIC_LETTERS. Anchors
     * (digits, and letters like B/D/E/H/K/etc. whose upper/lower forms are
     * shaped differently) give a reliable "this is what a full-height glyph
     * looks like in THIS rendering" reference; a case-symmetric letter
     * whose own height comes in well below that reference is the lower-case
     * form, not the upper-case one the isolated-crop classifier defaulted
     * to. If no anchor character exists in this CAPTCHA at all (referenceHeight
     * == 0 — every character happens to be case-symmetric), there is no
     * reliable reference to correct against, so the OCR's own case guess is
     * left untouched rather than risk a worse, ungrounded correction.
     */
    private CharGuess applyRelativeCaseCorrection(CharGuess guess, Segment seg, int referenceHeight) {
        if (referenceHeight <= 0) {
            return guess;
        }
        char c = guess.text.charAt(0);
        char upper = Character.toUpperCase(c);
        if (CASE_SYMMETRIC_LETTERS.indexOf(upper) < 0) {
            return guess;
        }
        double ratio = (double) seg.height() / referenceHeight;
        char corrected = ratio < caseHeightRatioThreshold ? Character.toLowerCase(upper) : upper;
        if (corrected != c) {
            log.debug("Relative-height case correction: [{}] (height {}px, {}% of the {}px reference "
                    + "glyph height in this CAPTCHA) -> [{}]",
                c, seg.height(), String.format("%.0f", ratio * 100), referenceHeight, corrected);
            return new CharGuess(String.valueOf(corrected), guess.confidence);
        }
        return guess;
    }

    /**
     * Applies the same relative-height case heuristic used by segmentedIdentify()
     * (see applyRelativeCaseCorrection() above) to an AI Vision answer instead
     * of an OCR one.
     *
     * Vision models are told in the prompt to judge case for case-symmetric
     * letters (CASE_SYMMETRIC_LETTERS: C/c, O/o, S/s, U/u, V/v, W/w, X/x, Z/z)
     * "from height relative to the other characters" — but that's asking a
     * vision-language model to do a precise geometric measurement from a
     * verbal instruction alone, which smaller/local models in particular are
     * unreliable at, even though they usually identify WHICH letter it is
     * correctly. Classical connected-component analysis measures that same
     * pixel height directly and exactly, so instead of trusting the model's
     * own case judgment, this keeps the model's character identity (its
     * shape-reading is generally fine) and overrides ONLY the case of
     * case-symmetric letters using the same referenceHeight-ratio logic
     * segmentedIdentify() already trusts for OCR.
     *
     * This requires connected-component segmentation to find EXACTLY as many
     * character segments as the vision answer has characters, in left-to-right
     * order, so each segment can be paired positionally with the model's
     * character at that index. If segmentation is disabled, the image can't
     * be segmented into that exact count (e.g. touching/overlapping glyphs
     * segmentation can't cleanly split), or anything else goes wrong, this
     * is a best-effort enhancement — it silently returns the vision answer
     * completely unchanged rather than risk corrupting an otherwise-correct
     * read. It never throws.
     */
    String applyRelativeCaseCorrectionToVisionAnswer(File captchaFile, String visionAnswer) {
        if (visionAnswer == null || visionAnswer.isEmpty() || !segmentationEnabled) {
            return visionAnswer;
        }
        try {
            BufferedImage processed = preprocessor.preprocess(captchaFile);

            List<Segment> raw = findConnectedComponents(processed);
            List<Segment> noBorder = removeBorderFrameComponents(raw, processed.getWidth(), processed.getHeight());
            List<Segment> denoised = filterNoiseComponents(noBorder);
            List<Segment> wholeGlyphs = mergeFragments(denoised);
            List<Segment> merged = splitTouchingCharactersAdaptive(wholeGlyphs, processed, visionAnswer.length());
            merged.sort(Comparator.comparingInt(s -> s.x0));

            if (merged.size() != visionAnswer.length()) {
                log.debug("Vision case-correction: connected-component segmentation found {} segment(s) but "
                        + "the AI Vision answer has {} character(s) — can't align segments to characters "
                        + "1:1, leaving the AI Vision case reading as-is: [{}]",
                    merged.size(), visionAnswer.length(), visionAnswer);
                return visionAnswer;
            }

            // Reference (full-glyph) height, exactly as segmentedIdentify() computes
            // it — but anchored on the VISION model's own character identities
            // rather than an OCR guess, since we're trusting the model for shape.
            int referenceHeight = 0;
            for (int i = 0; i < merged.size(); i++) {
                char upper = Character.toUpperCase(visionAnswer.charAt(i));
                if (CASE_SYMMETRIC_LETTERS.indexOf(upper) < 0) {
                    referenceHeight = Math.max(referenceHeight, merged.get(i).height());
                }
            }
            if (referenceHeight <= 0) {
                log.debug("Vision case-correction: every character in [{}] is case-symmetric — no anchor "
                    + "glyph to measure against, leaving the AI Vision case reading as-is.", visionAnswer);
                return visionAnswer;
            }

            StringBuilder corrected = new StringBuilder(visionAnswer.length());
            boolean anyChanged = false;
            for (int i = 0; i < visionAnswer.length(); i++) {
                char c = visionAnswer.charAt(i);
                char upper = Character.toUpperCase(c);
                if (CASE_SYMMETRIC_LETTERS.indexOf(upper) < 0) {
                    corrected.append(c);
                    continue;
                }
                double ratio = (double) merged.get(i).height() / referenceHeight;
                char fixed = ratio < caseHeightRatioThreshold ? Character.toLowerCase(upper) : upper;
                if (fixed != c) {
                    anyChanged = true;
                }
                corrected.append(fixed);
            }

            if (anyChanged) {
                log.info("📏 Vision case-correction: pixel-height measurement overrode the AI's own case "
                    + "reading -> [{}] (was: [{}])", corrected, visionAnswer);
                return corrected.toString();
            }
            return visionAnswer;

        } catch (Exception e) {
            log.debug("Vision case-correction skipped ({}) — leaving the AI Vision answer as-is: [{}]",
                e.getMessage(), visionAnswer);
            return visionAnswer;
        }
    }

    /**
     * Grid-based segmentation: divides the image into N equal vertical slices.
     * Used as a fallback when connected-component segmentation fails to find
     * the correct number of characters (e.g., when characters touch).
     *
     * Known limitation: every slice here is cropped to the full image
     * height by construction, so unlike segmentedIdentify()'s connected-
     * component path there is no per-character height signal to run
     * applyRelativeCaseCorrection() against — case-symmetric letters
     * (C/c, O/o, S/s, U/u, V/v, W/w, X/x, Z/z) solved via this fallback
     * keep whichever case the isolated-crop OCR pass happened to prefer.
     */
    private SegResult segmentByGrid(BufferedImage processed, int length) {
        try {
            int w = processed.getWidth();
            int h = processed.getHeight();
            int sliceWidth = w / length;
            if (sliceWidth <= 0) {
                return null;
            }

            StringBuilder text = new StringBuilder();
            List<Double> confidences = new ArrayList<>();

            for (int i = 0; i < length; i++) {
                int x0 = i * sliceWidth;
                int x1 = (i == length - 1) ? w : (i + 1) * sliceWidth;

                // Add horizontal padding to avoid cutting off character edges
                int pad = Math.max(4, sliceWidth / 4);
                int cropX0 = Math.max(0, x0 - pad);
                int cropX1 = Math.min(w, x1 + pad);

                Segment seg = new Segment(cropX0, 0, cropX1, h);
                CharGuess guess = identifySingleCharacter(processed, seg);
                if (guess == null) {
                    log.debug("Grid segmentation: could not classify slice {}", i);
                    return null;
                }
                text.append(guess.text);
                confidences.add(guess.confidence);
            }

            double avg = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            return new SegResult(text.toString(), avg);
        } catch (Exception e) {
            log.warn("Grid segmentation failed: {}", e.getMessage());
            return null;
        }
    }
    /**
     * Classifies exactly one isolated character crop. Runs Tesseract in PSM
     * 10 (treat image as a single character) three times with different
     * whitelists — the full configured charset, digits-only, and
     * letters-only — and keeps the highest-confidence result. Running
     * digits-only and letters-only passes separately (not just the combined
     * charset) is what lets a genuinely narrow classification win even when
     * the combined-charset pass's top guess was wrong: Tesseract's
     * confidence for "this is a 5" when only digits are legal candidates is
     * a cleaner signal than "this is a 5" when 'S' was also a legal
     * candidate the model had to rule out in the same pass.
     *
     * When the top two candidates disagree AND are a known confusable pair
     * (see CONFUSABLE_PAIRS) AND their confidences are close, a lightweight
     * geometric shape check (resolveConfusableByShape()) gets the final say
     * — see that method's javadoc for exactly which pairs it can actually
     * discriminate.
     */
    private CharGuess identifySingleCharacter(BufferedImage processed, Segment seg) {
        BufferedImage glyph = cropWithPadding(processed, seg, segPaddingPx);
        BufferedImage upscaled = upscale(glyph, segUpscale);

        List<CharGuess> candidates = new ArrayList<>();
        CharGuess full = ocrSingleChar(upscaled, textCaptchaCharset);
        if (full != null) {
            candidates.add(full);
        }
        CharGuess digitOnly = ocrSingleChar(upscaled, "0123456789");
        if (digitOnly != null) {
            candidates.add(digitOnly);
        }
        CharGuess letterOnly = ocrSingleChar(upscaled,
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
        if (letterOnly != null) {
            candidates.add(letterOnly);
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        CharGuess best = candidates.get(0);

        if (candidates.size() > 1) {
            CharGuess runnerUp = candidates.get(1);
            if (!best.text.equalsIgnoreCase(runnerUp.text)
                && isConfusablePair(best.text, runnerUp.text)
                && (best.confidence - runnerUp.confidence) < 15.0) {
                char resolved = resolveConfusableByShape(upscaled, best.text.charAt(0), runnerUp.text.charAt(0));
                if (resolved != 0 && Character.toUpperCase(resolved) != Character.toUpperCase(best.text.charAt(0))) {
                    log.debug("Shape heuristic overrode OCR for one segmented character: "
                            + "[{}] (confidence {}) -> [{}] (runner-up confidence {})",
                        best.text, String.format("%.1f", best.confidence),
                        resolved, String.format("%.1f", runnerUp.confidence));
                    best = new CharGuess(String.valueOf(resolved), runnerUp.confidence);
                }
            }
        }

        if (best.confidence < segConfidenceFloor) {
            log.debug("Segmented character [{}] confidence {} is below the configured floor {} — "
                    + "treating this character as unreadable.",
                best.text, String.format("%.1f", best.confidence), segConfidenceFloor);
            return null;
        }
        return best;
    }

    /**
     * Runs Tesseract PSM 10 (single character) against one already-isolated
     * glyph crop with the given whitelist, returning the top symbol and its
     * confidence, or null if nothing usable came back. Uses getWords() at
     * RIL_SYMBOL level rather than plain doOCR() specifically because
     * doOCR() has no confidence score to compare candidates against — this
     * whole per-character voting scheme depends on being able to rank
     * multiple whitelist passes against each other.
     *
     * The Tesseract instance is shared/mutable (page-seg-mode, whitelist),
     * so this synchronizes on it and always restores the constructor's
     * defaults (PSM 7, the full text charset) in a finally block — every
     * other method in this class assumes those defaults are in place when
     * it starts.
     */
    private CharGuess ocrSingleChar(BufferedImage glyph, String whitelist) {
        synchronized (tesseract) {
            try {
                tesseract.setPageSegMode(10);
                tesseract.setVariable("tessedit_char_whitelist", whitelist);
                List<Word> words = tesseract.getWords(glyph, ITessAPI.TessPageIteratorLevel.RIL_SYMBOL);
                if (words == null || words.isEmpty()) {
                    return null;
                }
                Word w = words.get(0);
                String rawText = w.getText();
                if (rawText == null) {
                    return null;
                }
                String text = rawText.trim();
                if (text.isEmpty()) {
                    return null;
                }
                // PSM 10 (single character mode) sometimes hallucinates multiple
                // characters (e.g., reading "Y" as "YW") if the crop has noise.
                // Since this is exactly one isolated segment, strictly enforce a
                // single-character output by keeping only the first character.
                if (text.length() > 1) {
                    log.debug("PSM 10 hallucinated multiple characters [{}] for a single "
                        + "segment — keeping only the first character.", text);
                    text = String.valueOf(text.charAt(0));
                }
                char c = text.charAt(0);
                if (whitelist.indexOf(c) < 0) {
                    return null;
                }
                return new CharGuess(String.valueOf(c), w.getConfidence());
            } catch (Exception e) {
                return null;
            } finally {
                tesseract.setPageSegMode(7);
                tesseract.setVariable("tessedit_char_whitelist", textCaptchaCharset);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Connected-component segmentation helpers
    // -----------------------------------------------------------------------


    /**
     * Drops any component that's almost certainly a decorative box/rule
     * border baked into the CAPTCHA template rather than a character — see
     * borderFrameRemovalEnabled/borderFrameMinDimRatio/borderFrameMaxDensity
     * javadoc above. Must run BEFORE filterNoiseComponents(), for two
     * reasons: (1) the border's huge bounding-box area would otherwise be
     * included as a "substantial" component there and skew the median area
     * that noise-filtering is based on, and (2) filterNoiseComponents()
     * only drops components that are too SMALL — a border frame is the
     * opposite problem (too big, too hollow) and would sail through that
     * check untouched.
     */
    private List<Segment> removeBorderFrameComponents(List<Segment> comps, int imgWidth, int imgHeight) {
        if (!borderFrameRemovalEnabled || comps.isEmpty()) {
            return comps;
        }
        List<Segment> filtered = new ArrayList<>();
        for (Segment s : comps) {
            boolean spansImage = s.width() >= imgWidth * borderFrameMinDimRatio
                && s.height() >= imgHeight * borderFrameMinDimRatio;
            boolean hollow = s.density() <= borderFrameMaxDensity;
            if (spansImage && hollow) {
                log.debug("Segmentation: dropping a {}x{} component (fill density {}) as a decorative "
                        + "border frame, not a character.",
                    s.width(), s.height(), String.format("%.3f", s.density()));
                continue;
            }
            filtered.add(s);
        }
        return filtered.isEmpty() ? comps : filtered;
    }

    /**
     * Splits components that are almost certainly TWO OR MORE touching/
     * overlapping characters merged into one connected component — see
     * touchingCharSplitEnabled/touchingCharWidthRatio/touchingCharValleyMaxRatio
     * javadoc above. Runs after filterNoiseComponents() (so noise specks
     * don't distort the single-character width estimate) and before
     * mergeFragments() (which handles the opposite problem — one glyph
     * accidentally broken into multiple components — and operates fine on
     * whatever this pass produces).
     *
     * Each component that qualifies is split, and BOTH halves are then
     * re-checked the same way (a work queue, not a single pass) — a font
     * that fuses two characters together can just as easily fuse three or
     * four, and a one-shot split would leave a 4-glyph blob as two
     * still-merged pairs instead of four real characters.
     */
    private List<Segment> splitTouchingCharacters(List<Segment> comps, BufferedImage binary, int knownExpectedLength) {
        return splitTouchingCharacters(comps, binary, knownExpectedLength,
            touchingCharWidthRatio, touchingCharValleyMaxRatio, MIN_SPLIT_HALF_RATIO);
    }

    /**
     * Same as above but with the three split thresholds passed explicitly,
     * so splitTouchingCharactersAdaptive() can re-run this against the same
     * starting components with progressively relaxed values without
     * touching the configured defaults (which other CAPTCHAs/sites still
     * rely on as-is).
     */
    private List<Segment> splitTouchingCharacters(List<Segment> comps, BufferedImage binary, int knownExpectedLength,
                                                  double widthRatio, double valleyMaxRatio, double minHalfRatio) {
        if (!touchingCharSplitEnabled || comps.isEmpty()) {
            return comps;
        }
        double referenceWidth = estimateSingleCharWidth(comps, knownExpectedLength);
        if (referenceWidth <= 0) {
            return comps;
        }

        Deque<Segment> pending = new ArrayDeque<>(comps);
        List<Segment> result = new ArrayList<>();
        // Safety cap, not an expected case: each successful split strictly
        // narrows its two halves versus the parent, so the queue naturally
        // drains as pieces fall under the width threshold. This just
        // guards against an unforeseen pathological image looping forever.
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 64) {
            Segment s = pending.poll();
            if (s.width() < referenceWidth * widthRatio) {
                result.add(s);
                continue;
            }
            List<Segment> split = trySplitByColumnValley(s, binary, referenceWidth, valleyMaxRatio, minHalfRatio);
            if (split.size() == 1) {
                result.add(s);
            } else {
                pending.addAll(split);
            }
        }
        result.sort(Comparator.comparingInt(x -> x.x0));
        return result;
    }

    /**
     * Retries splitTouchingCharacters() with progressively relaxed
     * thresholds when we KNOW (via knownExpectedLength) that the
     * conservative default pass came up short — see the adaptiveRelax
     * field javadoc above for why this is safe to do only in that specific
     * case. Each step starts fresh from the original wholeGlyphs rather
     * than compounding onto the previous (under-split) result, so a step's
     * outcome only ever depends on its own thresholds, not on whichever
     * splits the previous, stricter step happened to find.
     *
     * Stops as soon as a step produces exactly knownExpectedLength
     * segments. If every step is exhausted without hitting that count,
     * returns whichever attempt got closest (ties broken toward the LAST
     * — most relaxed — attempt, since more segments found is itself
     * evidence a real boundary was recovered) and lets the existing
     * grid-segmentation fallback in segmentedIdentify() take over from
     * there, exactly as it already does for the strict-only case.
     */
    private List<Segment> splitTouchingCharactersAdaptive(List<Segment> wholeGlyphs, BufferedImage binary,
                                                          int knownExpectedLength) {
        List<Segment> strict = splitTouchingCharacters(wholeGlyphs, binary, knownExpectedLength);
        if (knownExpectedLength <= 0 || !touchingCharAdaptiveRelaxEnabled || strict.size() >= knownExpectedLength) {
            // Nothing to relax toward (no known target), relaxation is
            // turned off, or we're already at/over the target — relaxing
            // further would only risk over-splitting a genuine glyph.
            return strict;
        }

        List<Segment> best = strict;
        for (int step = 1; step <= touchingCharMaxRelaxSteps && best.size() != knownExpectedLength; step++) {
            double widthRatio = Math.max(1.0, touchingCharWidthRatio - (touchingCharWidthRatioStep * step));
            double valleyMaxRatio = Math.min(VALLEY_MAX_RATIO_CEILING,
                touchingCharValleyMaxRatio + (touchingCharValleyMaxRatioStep * step));
            double minHalfRatio = Math.max(MIN_SPLIT_HALF_RATIO_FLOOR,
                MIN_SPLIT_HALF_RATIO - (touchingCharMinHalfRatioStep * step));

            List<Segment> relaxed = splitTouchingCharacters(wholeGlyphs, binary, knownExpectedLength,
                widthRatio, valleyMaxRatio, minHalfRatio);
            log.debug("Segmentation: strict pass gave {} segment(s) (need {}) — relax step {}/{} "
                    + "(widthRatio={}, valleyMaxRatio={}, minHalfRatio={}) gave {}.",
                strict.size(), knownExpectedLength, step, touchingCharMaxRelaxSteps,
                String.format("%.2f", widthRatio), String.format("%.2f", valleyMaxRatio),
                String.format("%.2f", minHalfRatio), relaxed.size());

            if (Math.abs(relaxed.size() - knownExpectedLength) <= Math.abs(best.size() - knownExpectedLength)) {
                best = relaxed;
            }
        }
        return best;
    }

    /**
     * Estimates the width of ONE character in this specific image — the
     * reference used to decide whether a component is actually two-or-more
     * merged characters. Prefers the CAPTCHA's known expected length (see
     * resolveExpectedLength()) when available: total horizontal ink span
     * (leftmost component's x0 to rightmost component's x1) divided by the
     * expected character count. That's far more reliable than a median of
     * the CURRENT component widths when several — or, as with a real
     * india.ai CAPTCHA image ("2fbd3" fusing into just 2 raw components
     * instead of 5), most — characters are already touching: a same-list
     * median in that situation is itself dominated by the already-merged,
     * too-wide components (or, with only 1-2 components total, has no
     * "normal-width" value to find at all), which is exactly the case this
     * method exists to handle. Falls back to the median component width
     * when the expected length isn't known (0).
     */
    private double estimateSingleCharWidth(List<Segment> comps, int knownExpectedLength) {
        if (knownExpectedLength > 0) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            for (Segment s : comps) {
                minX = Math.min(minX, s.x0);
                maxX = Math.max(maxX, s.x1);
            }
            double perChar = (double) (maxX - minX) / knownExpectedLength;
            if (perChar > 0) {
                return perChar;
            }
        }
        List<Integer> widths = new ArrayList<>();
        for (Segment s : comps) {
            widths.add(s.width());
        }
        Collections.sort(widths);
        return widths.get(widths.size() / 2);
    }

    /**
     * Looks for a column-ink-count valley in the middle band of one wide
     * component and, if one is found, splits the component there into two.
     * The search is restricted to the middle band (excluding the outer 1/6
     * of the width on each side) so a naturally thin serif or stroke-end
     * near a character's own edge is never mistaken for a boundary between
     * two different characters. Falls back to returning the component
     * unsplit — rather than guessing — whenever the deepest dip found isn't
     * convincingly low relative to the component's own peak ink column, or
     * either resulting half would be too narrow to plausibly be a real
     * character in this specific CAPTCHA (below referenceSingleCharWidth *
     * MIN_SPLIT_HALF_RATIO, not a fixed pixel count — a font whose real
     * glyphs only run ~10px wide needs a much lower floor than one whose
     * glyphs run ~30px, and a fixed floor can't tell the difference).
     * Called from splitTouchingCharacters(), which re-queues each returned
     * half to be checked again — so this only ever needs to find ONE valley
     * per call, not the full set for a component fusing 3+ characters.
     */
    private List<Segment> trySplitByColumnValley(Segment s, BufferedImage binary, double referenceSingleCharWidth,
                                                 double valleyMaxRatio, double minHalfRatio) {
        int w = s.width();
        int[] colCounts = new int[w];
        int maxCount = 0;
        for (int dx = 0; dx < w; dx++) {
            int x = s.x0 + dx;
            int count = 0;
            for (int y = s.y0; y < s.y1; y++) {
                if (isInk(binary, x, y)) {
                    count++;
                }
            }
            colCounts[dx] = count;
            maxCount = Math.max(maxCount, count);
        }
        if (maxCount == 0) {
            return List.of(s);
        }

        int margin = Math.max(2, w / 6);
        double valleyThreshold = maxCount * valleyMaxRatio;

        // Among every column in the search window that's plausibly a glyph
        // boundary (ink count under valleyThreshold — same bar as before),
        // pick the one that leaves BOTH resulting halves closest to one
        // real character's width, instead of simply the single darkest
        // column. A font that fuses characters with a connecting arm or
        // ligature (confirmed on a real SAHMAT "rd" pair) produces a WIDE,
        // gradually-changing low-ink plateau rather than one sharp dip —
        // the true boundary can sit anywhere across it, and always taking
        // the plateau's first (leftmost) column, as an earlier version of
        // this method did, can land INSIDE one glyph's own connecting
        // stroke rather than at the real boundary: observed to slice a
        // real "r" down to a bare stem (unrecognizable — the arm stayed
        // attached to the neighboring "d" instead) even though the ink-
        // count check itself was satisfied. Scoring every plausible column
        // by how width-balanced the resulting split is corrects this
        // without assuming a fixed direction (favor-left vs favor-right)
        // that would only happen to hold for this one font/pair.
        int bestDx = -1;
        double bestScore = Double.MAX_VALUE;
        for (int dx = margin; dx < w - margin; dx++) {
            if (colCounts[dx] > valleyThreshold) {
                continue;
            }
            double leftWidth = dx;
            double rightWidth = w - dx;
            double score = Math.abs(leftWidth - referenceSingleCharWidth) + Math.abs(rightWidth - referenceSingleCharWidth);
            if (score < bestScore) {
                bestScore = score;
                bestDx = dx;
            }
        }
        if (bestDx < 0) {
            return List.of(s);
        }

        int splitX = s.x0 + bestDx;
        Segment left = rebuildSegmentFromBinary(s.x0, s.y0, splitX, s.y1, binary);
        Segment right = rebuildSegmentFromBinary(splitX, s.y0, s.x1, s.y1, binary);
        double minHalfWidth = referenceSingleCharWidth * minHalfRatio;
        if (left.width() < minHalfWidth || right.width() < minHalfWidth) {
            return List.of(s);
        }
        log.debug("Segmentation: splitting a {}px-wide component at column offset {} "
                + "(width-balanced against reference {}px, ink count {} vs peak {}) — likely two touching characters.",
            w, bestDx, String.format("%.1f", referenceSingleCharWidth), colCounts[bestDx], maxCount);
        return List.of(left, right);
    }

    // A candidate split is rejected if either resulting half would be
    // narrower than this fraction of one real character's estimated width
    // in this image — guards against a shallow/spurious valley (e.g. inside
    // the bowl of a single "n") producing a sliver-plus-remainder instead
    // of two real characters. 0.4 is deliberately lenient rather than ~0.5
    // (a true half-and-half split) since two touching characters rarely
    // split perfectly evenly in practice.
    private static final double MIN_SPLIT_HALF_RATIO = 0.4;

    /**
     * Rebuilds a Segment for a sub-region of the original image, with a real
     * (not assumed-solid) pixel count AND a re-tightened y0/y1 bounding box.
     *
     * Bug fixed here: this is called by trySplitByColumnValley() to build
     * the two halves after slicing a wide, touching-characters component
     * down the middle. The caller only knows a new x-range for each half —
     * it still passes the ORIGINAL (parent) component's y0/y1 straight
     * through unchanged. That's fine when both halves are similarly tall,
     * but when a short, case-symmetric lowercase letter (o/s/u/v/w/x/z —
     * see CASE_SYMMETRIC_LETTERS) is touching a taller neighbor with an
     * ascender or descender (e.g. real captcha "bpo33", where "b"+"p"+"o"
     * fuse into one blob before splitting), the short letter's half kept
     * reporting the TALL parent's full height instead of its own — which
     * fed a wrong (near 1.0, "looks uppercase") height ratio straight into
     * applyRelativeCaseCorrection()/applyRelativeCaseCorrectionToVisionAnswer(),
     * flipping an already-correct lowercase read (e.g. AI Vision's correct
     * "bpo33") into a wrong one ("bpO33"). Confirmed against a real captcha
     * screenshot (text_captcha_20260907_134151.png) where this silently
     * corrupted an otherwise-correct AI Vision answer.
     *
     * Fix: scan for the actual min/max y containing ink WITHIN this
     * sub-region and use that as the segment's real y0/y1, exactly the way
     * findConnectedComponents() derives a tight box for a genuine standalone
     * component. If the half has no ink at all (shouldn't normally happen,
     * but the caller/valley search could theoretically produce an empty
     * slice), fall back to the caller-supplied box rather than emit an
     * inverted (minY > maxY) range.
     */
    private Segment rebuildSegmentFromBinary(int x0, int y0, int x1, int y1, BufferedImage binary) {
        int pixelCount = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if (isInk(binary, x, y)) {
                    pixelCount++;
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (pixelCount == 0) {
            return new Segment(x0, y0, x1, y1, 0);
        }
        return new Segment(x0, minY, x1, maxY + 1, pixelCount);
    }

    /**
     * Drops components that are almost certainly background speckle rather
     * than a real character stroke: anything smaller than
     * (median surviving-component area * captcha.segmentation.minComponentAreaRatio).
     * The median is computed only from components with area >= 6px to avoid
     * a CAPTCHA whose noise specks vastly outnumber real characters dragging
     * the median itself down to noise-sized. If filtering would remove
     * every component (a pathological/very noisy image), the original
     * unfiltered list is returned instead of an empty one — segmentedIdentify()'s
     * own min/max character-count check is the real backstop in that case.
     */
    private List<Segment> filterNoiseComponents(List<Segment> comps) {
        if (comps.isEmpty()) {
            return comps;
        }
        List<Integer> substantialAreas = new ArrayList<>();
        for (Segment s : comps) {
            if (s.area() >= 6) {
                substantialAreas.add(s.area());
            }
        }
        if (substantialAreas.isEmpty()) {
            return comps;
        }
        Collections.sort(substantialAreas);
        int median = substantialAreas.get(substantialAreas.size() / 2);
        double minArea = median * segMinComponentAreaRatio;

        List<Segment> filtered = new ArrayList<>();
        for (Segment s : comps) {
            if (s.area() >= minArea && s.width() >= 2 && s.height() >= 2) {
                filtered.add(s);
            }
        }
        return filtered.isEmpty() ? comps : filtered;
    }

    /**
     * Merges components that are almost certainly fragments of ONE glyph
     * rather than two separate characters — e.g. the dot of an "i"/"j"
     * separated from its stem, or a stroke broken in two by median-filter
     * denoising. Two components are merged when either:
     *   - one of them is tiny (area < 40px, i.e. plausibly a dot/fragment,
     *     not a full small character like "." or "l"), and their x-ranges
     *     overlap at all, OR
     *   - their x-ranges overlap by at least 60% of the narrower one's width
     *     (a genuine same-glyph split, not just two adjacent characters in
     *     a slanted/italic font whose bounding boxes happen to touch).
     * The 60% threshold is deliberately conservative — merging two truly
     * separate characters is a worse failure than occasionally leaving two
     * fragments of the same glyph unmerged (which just fails the
     * min/maxChars check and falls back to whole-string OCR instead of
     * producing a wrong answer).
     */
    private List<Segment> mergeFragments(List<Segment> comps) {
        if (comps.size() <= 1) {
            return new ArrayList<>(comps);
        }
        List<Segment> merged = new ArrayList<>(comps);
        merged.sort(Comparator.comparingInt(s -> s.x0));

        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < merged.size(); i++) {
                for (int j = i + 1; j < merged.size(); j++) {
                    Segment a = merged.get(i);
                    Segment b = merged.get(j);
                    int overlap = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0);
                    if (overlap <= 0) {
                        continue;
                    }
                    boolean tinyFragment = a.area() < 40 || b.area() < 40;
                    int narrowerWidth = Math.min(a.width(), b.width());
                    boolean strongOverlap = narrowerWidth > 0 && overlap >= narrowerWidth * 0.6;
                    if (tinyFragment || strongOverlap) {
                        Segment combined = new Segment(
                            Math.min(a.x0, b.x0), Math.min(a.y0, b.y0),
                            Math.max(a.x1, b.x1), Math.max(a.y1, b.y1),
                            a.pixelCount + b.pixelCount);
                        merged.remove(j);
                        merged.remove(i);
                        merged.add(i, combined);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        merged.sort(Comparator.comparingInt(s -> s.x0));
        return merged;
    }

    /**
     * Crops one component out of the full image with a white padding margin,
     * ready for per-character OCR.
     *
     * The padding margin is always BLANK — added around the crop, never read
     * from the source image. An earlier version read the padding pixels
     * directly from src (i.e. expanded the source-read rectangle to
     * [seg.x0-padding, seg.x1+padding] and copied that whole region), which
     * is only safe when a component sits in genuine, generous whitespace.
     * Two common cases break that assumption and were silently corrupting
     * OCR input:
     *   1. A segment produced by splitTouchingCharacters() sits, by
     *      definition, directly against real ink on at least one side (the
     *      sibling half it was split from, and/or the neighbor it was
     *      touching) — there is often less than `padding` pixels of real
     *      gap there at all.
     *   2. Even naturally-separate, un-split components can be more tightly
     *      kerned than `padding` on some fonts/sites (observed: 3px real
     *      gap between two components on a `padding=6` config) — the old
     *      code would still reach 3px into the neighbor's own ink.
     * Either way, the OLD crop pulled a stray fragment of the ADJACENT
     * character's stroke into this character's crop — confirmed against a
     * real SAHMAT CAPTCHA ("axrdr"): the isolated "x" crop picked up a
     * sliver of the next character's ("r") stem on its right edge purely
     * from the padding reaching past x1 into where "r" begins, which was
     * enough to make Tesseract read the corrupted glyph as "D" instead of
     * "x" — a plausible OCR error given the added stroke, but nothing to do
     * with the OCR engine or the segmentation boundary itself being wrong.
     *
     * Fix: read ONLY the segment's own tight bounding box from src (that
     * box already comes from real measured ink extents — see
     * findConnectedComponents()/rebuildSegmentFromBinary()), then place it
     * inside a larger all-white canvas. The padding border is therefore
     * guaranteed blank regardless of how close a real neighbor's ink is.
     */
    private BufferedImage cropWithPadding(BufferedImage src, Segment seg, int padding) {
        int w = src.getWidth();
        int h = src.getHeight();
        int sx0 = Math.max(0, seg.x0);
        int sy0 = Math.max(0, seg.y0);
        int sx1 = Math.min(w, seg.x1);
        int sy1 = Math.min(h, seg.y1);
        int segW = Math.max(1, sx1 - sx0);
        int segH = Math.max(1, sy1 - sy0);

        int cw = segW + 2 * padding;
        int ch = segH + 2 * padding;

        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, cw, ch);
        g.drawImage(src, padding, padding, padding + segW, padding + segH, sx0, sy0, sx1, sy1, null);
        g.dispose();
        return out;
    }

    /**
     * Nearest-neighbour upscale of an already-binarized crop. Deliberately
     * NOT bicubic (unlike preprocess()'s initial 2x upscale of the raw
     * screenshot) — the source here is already pure black/white, so a
     * smoothing interpolation would reintroduce grey anti-aliased edges
     * right before OCR, undoing the thresholding step.
     */
    private BufferedImage upscale(BufferedImage src, int factor) {
        if (factor <= 1) {
            return src;
        }
        int nw = src.getWidth() * factor;
        int nh = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    // -----------------------------------------------------------------------
    // Confusable-character shape heuristics
    // -----------------------------------------------------------------------

    /**
     * Case-insensitive: CONFUSABLE_PAIRS is keyed on specific cases (e.g.
     * '9' -> {'g','q'}), but OCR candidates can come back in either case
     * (e.g. '9' vs 'Q' from the letters-only pass) — matching only the exact
     * case previously meant this tiebreak silently never fired for anything
     * but a lucky case match, which is most real CAPTCHA output.
     */
    private boolean isConfusablePair(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        char ca = a.charAt(0);
        char cb = b.charAt(0);
        if (isConfusablePairExact(ca, cb) || isConfusablePairExact(cb, ca)) {
            return true;
        }
        return isConfusablePairExact(Character.toUpperCase(ca), Character.toUpperCase(cb))
            || isConfusablePairExact(Character.toLowerCase(ca), Character.toLowerCase(cb))
            || isConfusablePairExact(Character.toUpperCase(cb), Character.toUpperCase(ca))
            || isConfusablePairExact(Character.toLowerCase(cb), Character.toLowerCase(ca));
    }

    private boolean isConfusablePairExact(char a, char b) {
        char[] partners = CONFUSABLE_PAIRS.get(a);
        if (partners == null) {
            return false;
        }
        for (char c : partners) {
            if (c == b) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort geometric tiebreaker between two OCR candidates that are a
     * known confusable pair and landed close in confidence. This is
     * deliberately a SMALL set of rules with a real, explainable shape
     * signal behind each one — not a general character classifier, and it
     * only ever runs as a tiebreaker (see identifySingleCharacter()), never
     * as the primary classification:
     *   - 5 vs S: a digit "5" has a straight horizontal bar across its top,
     *     including the top-left corner; "S" curves away from the top-left
     *     corner. High ink density in the top-left region -> '5'.
     *   - 8 vs B: "B" has a straight vertical stroke down its entire left
     *     edge; "8" narrows ("waists") in the middle of its left edge where
     *     the two loops meet. High, near-uniform ink density down the whole
     *     left edge -> 'B'.
     *   - 6 vs G: "6" is a fully closed loop (one enclosed hole); "G" is
     *     open on the right side in most sans-serif fonts (no fully enclosed
     *     hole). Hole count >= 1 -> '6'.
     *   - 9 vs g/q: same closed-loop logic as 6/G, at the top of the glyph
     *     instead of the bottom.
     *   - 0 vs O: intentionally NOT handled here — there is no shape rule
     *     that reliably distinguishes them across real-world CAPTCHA fonts
     *     (some render '0' narrower or slashed, many don't at all), so this
     *     pair is left entirely to whichever whitelist pass was more
     *     confident.
     * Returns 0 (not '0' the digit — the null character) when no rule
     * applies, meaning "don't override, keep the higher-confidence OCR
     * candidate as-is".
     */
    private char resolveConfusableByShape(BufferedImage glyph, char a, char b) {
        try {
            char upperA = Character.toUpperCase(a);
            char upperB = Character.toUpperCase(b);

            // IMPROVED: 5 vs S/s. '5' has a flat top bar and a vertical left stroke; 's'/'S' curves inward.
            // Checking both the top-left corner and the upper-left vertical stroke is much more robust
            // across different CAPTCHA fonts than the previous generic top-left density block.
            if (isPair(upperA, upperB, '5', 'S')) {
                double topLeftCorner = regionDensity(glyph, 0.0, 0.0, 0.3, 0.2);
                double leftStroke = regionDensity(glyph, 0.0, 0.2, 0.25, 0.3);
                double fiveScore = topLeftCorner + leftStroke;
                // '5' will have high density in these structural regions; 's'/'S' will be low.
                return fiveScore > 0.15 ? pickCase('5', a, b) : pickCase('s', a, b);
            }

            if (isPair(upperA, upperB, '8', 'B')) {
                double leftEdgeDensity = regionDensity(glyph, 0.0, 0.0, 0.2, 1.0);
                return leftEdgeDensity > 0.55 ? pickCase('B', a, b) : pickCase('8', a, b);
            }

            if (isPair(upperA, upperB, '6', 'G')) {
                int holes = countHoles(glyph);
                return holes >= 1 ? pickCase('6', a, b) : pickCase('G', a, b);
            }

            if (isPair(upperA, upperB, '9', 'G') || isPair(upperA, upperB, '9', 'Q')) {
                int holes = countHoles(glyph);
                char digitNine = (upperA == '9') ? a : b;
                char letter = (upperA == '9') ? b : a;
                return holes >= 1 ? pickCase('9', digitNine, digitNine) : letter;
            }

            // NEW: m vs n. 'm' is significantly wider (two humps) than 'n' (one hump).
            if (isPair(upperA, upperB, 'M', 'N')) {
                double aspectRatio = (double) glyph.getWidth() / glyph.getHeight();
                // 'm' typically has an aspect ratio > 0.75, 'n' is narrower (< 0.75)
                return aspectRatio > 0.75 ? pickCase('m', a, b) : pickCase('n', a, b);
            }

            // NEW: h vs n. 'h' has a tall vertical left stroke; 'n' does not.
            if (isPair(upperA, upperB, 'H', 'N')) {
                double leftEdgeTop = regionDensity(glyph, 0.0, 0.0, 0.2, 0.6);
                return leftEdgeTop > 0.25 ? pickCase('h', a, b) : pickCase('n', a, b);
            }

            // NEW: c vs e. 'e' has a horizontal middle bar; 'c' is open.
            if (isPair(upperA, upperB, 'C', 'E')) {
                double middleBar = regionDensity(glyph, 0.2, 0.4, 0.6, 0.2);
                return middleBar > 0.20 ? pickCase('e', a, b) : pickCase('c', a, b);
            }

            // NEW: a/A vs '@'. '@' wraps a near-full circular stroke around
            // its inner loop, filling substantially more of its bounding
            // box than the simpler two-stroke shape of 'a'/'A' — a plain
            // letter's enclosed counter is a minority of the glyph area,
            // '@'s spiral covers most of it. Overall ink density is a much
            // more reliable signal here than hole-count (both shapes have
            // exactly one enclosed region), and this is the pair actually
            // reported as commonly misread (letters coming back as '@').
            if (isPair(upperA, upperB, 'A', '@')) {
                double overallDensity = regionDensity(glyph, 0.0, 0.0, 1.0, 1.0);
                return overallDensity > 0.32 ? '@' : pickCase('a', a, b);
            }

            // 0/O and any other unlisted pair: no reliable generic rule.
        } catch (Exception e) {
            log.debug("Shape-heuristic tiebreak failed ({}) — keeping the higher-confidence OCR candidate", e.getMessage());
        }
        return 0;
    }

    private boolean isPair(char upperA, char upperB, char x, char y) {
        return (upperA == x && upperB == y) || (upperA == y && upperB == x);
    }

    /** Returns whichever of a/b (preserving its original OCR-reported case) matches the target character, case-insensitively. */
    private char pickCase(char canonical, char a, char b) {
        if (Character.toUpperCase(a) == Character.toUpperCase(canonical)) {
            return a;
        }
        if (Character.toUpperCase(b) == Character.toUpperCase(canonical)) {
            return b;
        }
        return canonical;
    }

    /** Fraction of ink pixels within the given fractional sub-rectangle of the glyph (0.0-1.0 coordinates). */
    private double regionDensity(BufferedImage glyph, double xFrac, double yFrac, double wFrac, double hFrac) {
        int w = glyph.getWidth();
        int h = glyph.getHeight();
        int x0 = (int) (xFrac * w);
        int y0 = (int) (yFrac * h);
        int x1 = Math.min(w, (int) ((xFrac + wFrac) * w));
        int y1 = Math.min(h, (int) ((yFrac + hFrac) * h));
        int ink = 0;
        int total = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                total++;
                if (isInk(glyph, x, y)) {
                    ink++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) ink / total;
    }

    /**
     * Counts fully-enclosed background regions ("holes") in a glyph — e.g.
     * 1 for "O"/"0"/"6"/"9"/"D"/"P", 2 for "8"/"B", 0 for most open shapes
     * like "S"/"Z"/"L". Implemented as: flood-fill the background from every
     * border pixel (anything reachable from the outside can't be a hole),
     * then count the remaining connected background regions that were never
     * reached.
     */
    private int countHoles(BufferedImage glyph) {
        int w = glyph.getWidth();
        int h = glyph.getHeight();
        boolean[][] isBackground = new boolean[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                isBackground[x][y] = !isInk(glyph, x, y);
            }
        }

        boolean[][] reachable = new boolean[w][h];
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            seedIfBackground(x, 0, isBackground, reachable, queue);
            seedIfBackground(x, h - 1, isBackground, reachable, queue);
        }
        for (int y = 0; y < h; y++) {
            seedIfBackground(0, y, isBackground, reachable, queue);
            seedIfBackground(w - 1, y, isBackground, reachable, queue);
        }
        int[] dx4 = {-1, 1, 0, 0};
        int[] dy4 = {0, 0, -1, 1};
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (int d = 0; d < 4; d++) {
                int nx = p[0] + dx4[d];
                int ny = p[1] + dy4[d];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && isBackground[nx][ny] && !reachable[nx][ny]) {
                    reachable[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }

        boolean[][] visited = new boolean[w][h];
        int holes = 0;
        Deque<int[]> stack = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (isBackground[x][y] && !reachable[x][y] && !visited[x][y]) {
                    holes++;
                    visited[x][y] = true;
                    stack.push(new int[]{x, y});
                    while (!stack.isEmpty()) {
                        int[] p = stack.pop();
                        for (int d = 0; d < 4; d++) {
                            int nx = p[0] + dx4[d];
                            int ny = p[1] + dy4[d];
                            if (nx >= 0 && nx < w && ny >= 0 && ny < h
                                && isBackground[nx][ny] && !reachable[nx][ny] && !visited[nx][ny]) {
                                visited[nx][ny] = true;
                                stack.push(new int[]{nx, ny});
                            }
                        }
                    }
                }
            }
        }
        return holes;
    }

    private void seedIfBackground(int x, int y, boolean[][] isBackground, boolean[][] reachable, Deque<int[]> queue) {
        if (isBackground[x][y] && !reachable[x][y]) {
            reachable[x][y] = true;
            queue.add(new int[]{x, y});
        }
    }
}
