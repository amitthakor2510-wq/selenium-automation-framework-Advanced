package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CaptchaSolver - Integrated with Keyword Driven Framework AND with
 * automatic detection for plain Page-Object-Model tests.
 *
 * Two ways this gets used, both "wired" without per-test authoring:
 *
 *  1. EXPLICIT (keyword-driven tests): a data row uses one of the three
 *     keywords below, naming exactly which element is the CAPTCHA image and
 *     which is the answer field. See KeywordEngine.execute().
 *       - SOLVE_TEXT_CAPTCHA   - OCR based text CAPTCHA (routes through AI
 *                                Vision first if captcha.ai.enabled=true,
 *                                falling back to OCR only if that call fails)
 *       - SOLVE_MATH_CAPTCHA   - Mathematical equation CAPTCHA
 *       - SOLVE_CAPTCHA_WITH_AI - AI Vision (Anthropic Claude by default,
 *                                see resolveWithVisionApi()), always used
 *                                regardless of captcha.ai.enabled, with an
 *                                OCR fallback if the API call itself fails
 *
 *  2. AUTOMATIC (every Page Object, old and new, and every mobile screen):
 *     BasePage.navigateTo(...) and BaseMobilePage's screen-ready hook both
 *     call the static detect*() methods here after the page/screen loads.
 *     No page object anywhere has to know CAPTCHAs exist — if a login/
 *     register/etc. page happens to render a text/math image CAPTCHA that
 *     matches the common id/class/name/alt patterns below, it gets solved
 *     automatically; if it doesn't, detection is a handful of cheap
 *     findElements() calls and the page continues completely normally.
 *     Toggle with config key captcha.autoDetect.enabled (default: true).
 *     Because this lives in BasePage/BaseMobilePage — the parent of every
 *     Page Object / screen object in the framework, existing and future —
 *     adding a new site or a new page never requires touching CaptchaSolver
 *     or wiring anything by hand.
 *
 * reCAPTCHA/hCaptcha (image-tile / "I'm not a robot" challenges) are
 * detected separately and deliberately NOT attempted via OCR — they are not
 * solvable that way, and DemoQA's own register-page ReCaptcha check is a
 * documented example (see RegistrationPage.isBlockedByRecaptcha()). Auto-
 * detect logs a clear warning and backs off instead of wasting time trying
 * to OCR something that can never work.
 *
 * OCR ACCURACY PIPELINE (when AI Vision is disabled or unavailable):
 * resolveViaOcr() is the entry point used by both solveTextCaptcha() and
 * solveWithAI()'s fallback. It runs two independent OCR strategies against
 * the same preprocessed image and prefers whichever one actually produced a
 * usable answer:
 *   1. segmentedIdentify() — the primary, more accurate path. The image is
 *      split into individual character blobs via connected-component
 *      analysis (findConnectedComponents), fragments belonging to the same
 *      glyph are merged (mergeFragments — e.g. the dot of an "i"), noise
 *      speckles are dropped (filterNoiseComponents), and every remaining
 *      character is OCR'd ALONE (identifySingleCharacter/ocrSingleChar,
 *      Tesseract PSM 10) instead of as part of a string. Reading one glyph
 *      at a time — with no neighbouring strokes bleeding into the
 *      recognition window — is what actually fixes letter/digit confusion
 *      (0/O, 1/I/l, 5/S, 6/G, 8/B, 9/g...), and resolveConfusableByShape()
 *      adds a lightweight geometric tiebreaker (hole count, quadrant ink
 *      density, aspect ratio) for exactly those confusable pairs when two
 *      whitelist passes land close in confidence. Segmentation silently
 *      backs off (returns null) if the image doesn't cleanly separate into
 *      a plausible number of components (captcha.segmentation.minChars/
 *      maxChars) — it is not attempted on CAPTCHAs whose glyphs touch or
 *      overlap too much to isolate reliably.
 *   2. identifyText() — the original whole-string PSM 7/8 OCR pass, used
 *      whenever segmentation isn't applicable.
 * preprocessImage() itself also gained contrast normalization
 * (normalizeContrast), long thin decorative-line removal
 * (removeLongThinLines), and skew correction (deskew) ahead of the existing
 * median-filter denoise + Otsu threshold — all of which help segmentation
 * produce cleaner, more separable character blobs, not just whole-string OCR.
 */
public class CaptchaSolver {

    private static final Logger log = LoggerFactory.getLogger(CaptchaSolver.class);
    private final ITesseract tesseract;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // AI Vision config (used by solveWithAI, and by solveTextCaptcha when
    // captcha.ai.enabled=true routes the OCR keywords through vision too —
    // see solveTextCaptcha's javadoc). Nothing here is hardcoded: distorted-
    // font CAPTCHAs (wavy baselines, confusable glyph shapes like n/m) are
    // exactly what classical OCR engines like Tesseract struggle with even
    // after preprocessing/dictionary tuning, because that distortion is
    // deliberately designed to defeat pattern-matching OCR. A vision-capable
    // LLM reads the actual shapes in context rather than matching against a
    // fixed glyph library, which is why this is the real fix rather than
    // further threshold/denoise tuning.
    private final boolean aiEnabled = ConfigReader.getBoolean("captcha.ai.enabled", false);
    // captcha.ai.provider: "anthropic" (default, unchanged behavior) or
    // "ollama" — a local/remote Ollama server running a vision-capable model
    // (e.g. llava). Ollama's /api/generate has a completely different
    // request/response JSON shape than Anthropic's Messages API (images go
    // in a flat base64 "images" array, not a content-block "source" object;
    // the answer comes back as a top-level "response" string, not a
    // "content" block array) and needs no API key — so this is a real
    // branch below, not just a different endpoint URL plugged into the same
    // Anthropic-shaped request.
    private final String aiProvider = ConfigReader.get("captcha.ai.provider", "anthropic").trim().toLowerCase();
    private final boolean isOllama = "ollama".equals(aiProvider);
    private final String aiApiKey = ConfigReader.get("captcha.ai.apiKey",
        System.getenv().getOrDefault("ANTHROPIC_API_KEY", ""));
    private final String aiModel = ConfigReader.get("captcha.ai.model", "");
    private final String aiEndpoint = ConfigReader.get("captcha.ai.endpoint",
        isOllama ? "http://localhost:11434/api/generate" : "https://api.anthropic.com/v1/messages");

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
    // Static fallback if the input field has no maxlength attribute at all —
    // see resolveExpectedLength(), which is what solveTextCaptcha() actually
    // uses per-call. Kept for sites where the CAPTCHA answer field doesn't
    // set maxlength but has a known fixed length anyway.
    private final int expectedLength = ConfigReader.getInt("captcha.expected.length", 0);
    // How long to wait for the CAPTCHA <img> itself to actually finish
    // loading (img.complete && naturalWidth > 0) before screenshotting it.
    // Separate from WAIT_FOR_PAGE_LOAD's document.readyState check — an SPA
    // can report readyState=='complete' (or time out waiting for it) well
    // before an async-rendered CAPTCHA image has actually finished
    // downloading, and screenshotting/solving a still-broken <img> produces
    // garbage that isn't a CAPTCHA-reading-accuracy problem at all (it's
    // whatever the browser renders for a broken image — an icon plus its
    // alt text, on Chrome).
    private final int captchaImageLoadTimeoutSeconds = ConfigReader.getInt("captcha.image.load.timeout", 10);

    /**
     * Live per-call expected CAPTCHA length: the answer input field's own
     * HTML maxlength attribute if present and numeric, otherwise the live
     * DOM maxLength JS property, otherwise the static captcha.expected.length
     * config, otherwise 0 (no length check — the segmentation/whole-string
     * OCR results are trusted as-is).
     *
     * maxlength wins over the static config because it's read straight off
     * the live field on this exact page — no per-site config wiring
     * required — and it's what actually caught the original bug this exists
     * for: segmentation over-reading a CAPTCHA by 1-4 spurious characters
     * (noise picked up as a fake glyph, or one glyph split into two) still
     * passes the broad segMinChars/segMaxChars(3-12) sanity check, so an
     * over-long answer was being typed and silently truncated by the
     * field's own maxlength — which looked like "the field is dropping
     * characters" when the real problem was upstream: the solved answer
     * was already wrong before it was ever typed.
     *
     * The JS property fallback exists because this exact gap showed up
     * against a real field (india.ai's staging CAPTCHA input): getAttribute
     * ("maxlength") returned null — no maxlength attribute in the markup —
     * even though the field visibly enforced a fixed length via its own JS
     * input handler. HTMLInputElement.maxLength (the DOM/IDL property, not
     * the HTML attribute) still reports the enforced cap in that case, so
     * this now checks it via a live executeScript call before falling back
     * to the static config. -1 is the browser's own "unset" sentinel for
     * this property — anything <= 0 is treated as not-set here too.
     */
    private int resolveExpectedLength(WebDriver driver, WebElement captchaInputField) {
        try {
            String maxlength = captchaInputField.getAttribute("maxlength");
            if (maxlength != null && !maxlength.isBlank()) {
                int parsed = Integer.parseInt(maxlength.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read/parse the CAPTCHA input field's maxlength attribute ({}) — "
                + "checking the live DOM maxLength property next", e.getMessage());
        }

        try {
            Object raw = ((JavascriptExecutor) driver).executeScript("return arguments[0].maxLength;", captchaInputField);
            if (raw instanceof Number) {
                int fromProperty = ((Number) raw).intValue();
                if (fromProperty > 0) {
                    log.debug("Resolved expected CAPTCHA length ({}) from the live DOM maxLength property "
                        + "(no maxlength HTML attribute was present)", fromProperty);
                    return fromProperty;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the CAPTCHA input field's DOM maxLength property ({}) — "
                + "falling back to captcha.expected.length", e.getMessage());
        }

        return expectedLength;
    }

    // How many pixels of white space to pad around each isolated character
    // crop before OCR-ing it alone. Tesseract (like most OCR engines) reads
    // glyphs worse when they're flush against the image edge — a small
    // margin gives PSM 10 the same "breathing room" a full CAPTCHA strip
    // naturally has around its first/last character.
    private final int segPaddingPx = ConfigReader.getInt("captcha.segmentation.paddingPx", 6);

    // Each isolated single-character crop is upscaled by this factor (on top
    // of preprocessImage()'s own 2x) before OCR — small crops (a single
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

    // -----------------------------------------------------------------------
    // Additional preprocessing toggles (see preprocessImage()). Both default
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
        Map.entry('a', new char[]{'@'}),
        Map.entry('A', new char[]{'@'}),
        Map.entry('@', new char[]{'a', 'A'}),
        Map.entry('e', new char[]{'c'})
    );

    // Every character CaptchaSolver will recognize/type for a text CAPTCHA —
    // upper AND lower case letters, digits, and the special characters most
    // CAPTCHA generators actually use. Previously this was uppercase+digits
    // ONLY, and cleanOCRText() additionally forced everything to uppercase
    // and stripped anything non-alphanumeric — so any CAPTCHA that actually
    // contained a lowercase letter or a symbol was guaranteed to be typed
    // wrong (Tesseract itself couldn't even consider those characters as
    // candidates, whitelist violations get silently dropped/misread). The
    // symbol set is deliberately a curated list of what real CAPTCHA
    // generators use (not e.g. quotes/backslashes, which would just make
    // whitelist-based recognition noisier), and is overridable per site via
    // captcha.text.charset if a particular CAPTCHA uses something outside
    // this default set.
    private static final String DEFAULT_TEXT_CHARSET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$%&*!?";

    private final String textCaptchaCharset;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public CaptchaSolver() {
        tesseract = new Tesseract();

        String tessDataPath = resolveTessDataPath();
        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(7);   // PSM 7 – single text line  (good for CAPTCHAs)
        tesseract.setOcrEngineMode(3); // OEM 3 – LSTM + legacy

        textCaptchaCharset = ConfigReader.get("captcha.text.charset", DEFAULT_TEXT_CHARSET);
        tesseract.setVariable("tessedit_char_whitelist", textCaptchaCharset);

        // Disable Tesseract's built-in dictionary/word-frequency correction.
        // By default Tesseract tries to nudge ambiguous glyphs toward real
        // English words (and toward the shapes it has seen most often in
        // real text), which is exactly wrong for a CAPTCHA string — those
        // are deliberately random characters, not words. Left enabled, this
        // is a very common cause of "most characters are right, a few are
        // wrong": commonly-confused glyph pairs (0/O, 1/l/I, 5/S, 8/B, etc.)
        // get silently "corrected" toward whichever one looks more like
        // real text/a real word, not toward what's actually in the image.
        tesseract.setVariable("load_system_dawg", "false");
        tesseract.setVariable("load_freq_dawg", "false");
        tesseract.setVariable("load_punc_dawg", "false");
        tesseract.setVariable("load_number_dawg", "false");
        tesseract.setVariable("tessedit_enable_dict_correction", "0");
        tesseract.setVariable("classify_enable_learning", "0");
        tesseract.setVariable("classify_enable_adaptive_matcher", "0");

        log.info("CaptchaSolver initialised with tessdata at: {}", tessDataPath);
    }

    /**
     * Double-valued config helper. ConfigReader only ships int/boolean/String
     * variants; kept local to this class rather than widening ConfigReader's
     * public contract for a couple of ratio/confidence knobs only this class
     * uses.
     */
    private static double getDoubleConfig(String key, double defaultValue) {
        String value = ConfigReader.get(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Axis-aligned bounding box of one connected component (candidate
     * character) found by findConnectedComponents(). x1/y1 are EXCLUSIVE
     * (i.e. this is [x0, x1) x [y0, y1)), matching normal image-processing
     * convention so width()/height() need no +1 correction.
     */
    private static final class Segment {
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

    /** A single OCR candidate for one isolated character, with its confidence (0-100, Tesseract scale). */
    private static final class CharGuess {
        final String text;
        final double confidence;

        CharGuess(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    /** Result of a full segmented (per-character) read of a CAPTCHA image. */
    private static final class SegResult {
        final String text;
        final double avgConfidence;

        SegResult(String text, double avgConfidence) {
            this.text = text;
            this.avgConfidence = avgConfidence;
        }
    }

    // -----------------------------------------------------------------------
    // Tessdata path resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the Tesseract tessdata directory, in priority order:
     *   1. -Dtesseract.datapath / config/{site,global}.properties'
     *      tesseract.datapath (same override pattern ConfigReader already
     *      uses for every other run-time setting in this framework)
     *   2. TESSDATA_PREFIX environment variable, if Tesseract itself is
     *      already configured on the host/container
     *   3. The first common install location that actually exists on disk
     *      for the current OS (previously this was hardcoded to the
     *      Windows path ONLY, which made every Linux/Docker CI run fail
     *      immediately with "TessDataPath ... does not exist", since this
     *      project's Dockerfile/docker-compose target Linux containers,
     *      not Windows)
     *
     * Fails fast with a clear, actionable message instead of letting
     * tess4j fail deep inside native code with an opaque error.
     */
    private String resolveTessDataPath() {
        String configured = ConfigReader.get("tesseract.datapath", "");
        if (!configured.isEmpty()) {
            if (!new File(configured).isDirectory()) {
                throw new ConfigException("[CaptchaSolver] tesseract.datapath is set to '" + configured
                    + "' but that directory does not exist. Fix the -Dtesseract.datapath / config value.");
            }
            return configured;
        }

        String envPrefix = System.getenv("TESSDATA_PREFIX");
        if (envPrefix != null && !envPrefix.isBlank() && new File(envPrefix).isDirectory()) {
            return envPrefix;
        }

        List<String> candidates = List.of(
            "/usr/share/tesseract-ocr/5/tessdata",       // Debian/Ubuntu, tesseract 5.x (this project's Docker image)
            "/usr/share/tesseract-ocr/4.00/tessdata",     // Debian/Ubuntu, tesseract 4.x
            "/usr/share/tesseract-ocr/tessdata",          // some distros
            "/usr/share/tessdata",                        // RHEL/Fedora/Alpine
            "/usr/local/share/tessdata",                  // Linux built-from-source, Intel macOS Homebrew
            "/opt/homebrew/share/tessdata",               // Apple Silicon macOS Homebrew
            "C:\\Program Files\\Tesseract-OCR\\tessdata"  // Windows default installer path
        );

        for (String candidate : candidates) {
            if (new File(candidate).isDirectory()) {
                return candidate;
            }
        }

        throw new ConfigException("[CaptchaSolver] Could not locate a tessdata directory. "
            + "Install tesseract-ocr (with the eng traineddata) and either let the standard install "
            + "path be auto-detected, set the TESSDATA_PREFIX environment variable, or pass "
            + "-Dtesseract.datapath=/path/to/tessdata explicitly.");
    }

    // -----------------------------------------------------------------------
    // Automatic detection (used by BasePage / BaseMobilePage / KeywordEngine)
    // -----------------------------------------------------------------------

    // Deliberately broad, case-insensitive (CSS ' i' flag — supported by
    // every browser this framework drives: Chrome, Firefox, Edge, Safari)
    // pattern match on id/class/name/alt/src, since real sites don't share
    // one naming convention. Ordered roughly most→least specific so the
    // first real match wins without scanning the whole DOM twice.
    private static final List<By> CAPTCHA_IMAGE_LOCATORS = List.of(
        By.cssSelector("img[id*='captcha' i]"),
        By.cssSelector("img[class*='captcha' i]"),
        By.cssSelector("img[name*='captcha' i]"),
        By.cssSelector("img[alt*='captcha' i]"),
        By.cssSelector("img[src*='captcha' i]"),
        By.cssSelector("canvas[id*='captcha' i]"),
        By.cssSelector("canvas[class*='captcha' i]"),
        By.cssSelector("[id*='captcha' i] img"),
        By.cssSelector("[class*='captcha' i] img")
    );

    private static final List<By> CAPTCHA_INPUT_LOCATORS = List.of(
        By.cssSelector("input[id*='captcha' i]"),
        By.cssSelector("input[name*='captcha' i]"),
        By.cssSelector("input[placeholder*='captcha' i]"),
        By.cssSelector("input[aria-label*='captcha' i]")
    );

    // reCAPTCHA/hCaptcha widgets — never attempted via OCR, see class javadoc.
    private static final List<By> UNSOLVABLE_CAPTCHA_LOCATORS = List.of(
        By.cssSelector("iframe[src*='recaptcha' i]"),
        By.cssSelector("iframe[title*='recaptcha' i]"),
        By.cssSelector(".g-recaptcha"),
        By.cssSelector("iframe[src*='hcaptcha' i]"),
        By.cssSelector("[class*='h-captcha' i]")
    );

    /**
     * True if a reCAPTCHA/hCaptcha widget is present. Callers should NOT
     * attempt solveTextCaptcha/solveMathCaptcha against these — OCR cannot
     * solve them, and DemoQA's registration flow is a real example of this
     * exact case (see RegistrationPage.isBlockedByRecaptcha()).
     */
    public static boolean isKnownUnsolvableCaptchaPresent(WebDriver driver) {
        for (By locator : UNSOLVABLE_CAPTCHA_LOCATORS) {
            if (!safeFind(driver, locator).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** First visible element that looks like an OCR-solvable CAPTCHA image, if any. */
    public static Optional<WebElement> detectCaptchaImage(WebDriver driver) {
        for (By locator : CAPTCHA_IMAGE_LOCATORS) {
            for (WebElement el : safeFind(driver, locator)) {
                if (isSafelyDisplayed(el)) {
                    return Optional.of(el);
                }
            }
        }
        return Optional.empty();
    }

    /** First visible element that looks like the CAPTCHA's answer input field, if any. */
    public static Optional<WebElement> detectCaptchaInputField(WebDriver driver) {
        for (By locator : CAPTCHA_INPUT_LOCATORS) {
            for (WebElement el : safeFind(driver, locator)) {
                if (isSafelyDisplayed(el)) {
                    return Optional.of(el);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Detects and solves a text CAPTCHA on the current page with zero
     * configuration, if (and only if) one is actually present. This is what
     * BasePage.navigateTo() / BaseMobilePage call after every page/screen
     * load — safe to call unconditionally on any page, including the vast
     * majority that have no CAPTCHA at all:
     *   - no image found                         -> returns null, no-op
     *   - reCAPTCHA/hCaptcha found instead        -> logs + returns null, no-op
     *   - image found but no matching input field -> logs a warning, returns null
     *   - image + input found                     -> OCR-solves and types the answer
     *
     * Never throws — a CAPTCHA-detection hiccup must never fail an unrelated
     * test, so failures are logged and swallowed here, one level above
     * solveTextCaptcha's own try/catch.
     */
    public String autoSolveIfPresent(WebDriver driver) {
        try {
            if (isKnownUnsolvableCaptchaPresent(driver)) {
                log.warn("⚠ reCAPTCHA/hCaptcha detected on page — cannot be auto-solved via OCR "
                    + "(needs a human, a paid solving service, or a test-account/API workaround). Skipping.");
                return null;
            }

            Optional<WebElement> image = detectCaptchaImage(driver);
            if (image.isEmpty()) {
                return null; // the overwhelmingly common case: no CAPTCHA on this page at all
            }

            Optional<WebElement> input = detectCaptchaInputField(driver);
            if (input.isEmpty()) {
                log.warn("⚠ CAPTCHA image auto-detected but no matching answer input field was found "
                    + "nearby — cannot auto-solve. Wire this page explicitly via SOLVE_TEXT_CAPTCHA "
                    + "with the correct ObjectRepository keys instead.");
                return null;
            }

            log.info("🔎 CAPTCHA auto-detected on page — attempting automatic OCR solve");
            return solveTextCaptcha(driver, image.get(), input.get());

        } catch (Exception e) {
            log.warn("⚠ CAPTCHA auto-detection failed (non-fatal, test continues): {}", e.getMessage());
            return null;
        }
    }

    private static List<WebElement> safeFind(WebDriver driver, By locator) {
        try {
            return driver.findElements(locator);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isSafelyDisplayed(WebElement el) {
        try {
            return el.isDisplayed();
        } catch (StaleElementReferenceException | org.openqa.selenium.NoSuchElementException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Public API used by KeywordEngine
    // -----------------------------------------------------------------------

    /**
     * SOLVE_TEXT_CAPTCHA keyword handler (also used by
     * SOLVE_TEXT_CAPTCHA_IF_PRESENT via KeywordEngine.solveTextCaptchaIfPresent).
     *
     * If captcha.ai.enabled=true, this delegates to the AI Vision solver
     * (see resolveWithVisionApi()) FIRST and only falls back to Tesseract
     * OCR if the API call itself fails (missing/invalid key, network error,
     * etc.) — never on a low-confidence read, since the API has no
     * confidence score to compare against OCR's. This means existing test
     * suites (like india.ai's keyword CSV) get the AI fix with zero test
     * changes once captcha.ai.enabled/apiKey/model are configured.
     *
     * With AI disabled (the default), behaves exactly as before: the image
     * is OCR'd (with a retry at a different Tesseract page-segmentation mode
     * if the first pass reads nothing usable), the full resulting string is
     * logged, and only THEN does typeIntoField() run. See identifyText() for
     * the actual two-pass OCR read.
     *
     * @return solved text
     */
    public String solveTextCaptcha(WebDriver driver,
                                   WebElement captchaImage,
                                   WebElement captchaInputField) {
        log.info("▶ solveTextCaptcha started");
        try {
            // 1. Screenshot CAPTCHA element (with a small margin — see
            //    screenshotElementWithMargin() javadoc — so glyphs that
            //    visually overflow the element's own CSS box aren't cropped
            //    off before OCR/Vision ever sees them)
            File captchaFile = screenshotElementWithMargin(driver, captchaImage, "text_captcha");

            // Prefer the input field's own maxlength over the static
            // captcha.expected.length config — it's read live from this
            // exact field on this exact page, so it's authoritative for
            // this site without needing per-site config, and it catches
            // exactly the failure mode that motivated this: segmentation
            // over-reading a CAPTCHA (extra spurious character(s) from
            // noise/split glyphs) past its real length, which the length
            // check below can then actually catch and correct via grid
            // segmentation instead of silently typing a too-long answer
            // that gets truncated by the field itself.
            int effectiveExpectedLength = resolveExpectedLength(driver, captchaInputField);

            String solved = null;

            if (aiEnabled) {
                try {
                    solved = solveViaVision(captchaFile, effectiveExpectedLength);
                    log.info("✅ Text CAPTCHA solved via AI Vision: [{}]", solved);
                } catch (Exception aiEx) {
                    log.warn("⚠ AI Vision solve failed ({}) — falling back to OCR for this CAPTCHA",
                        aiEx.getMessage());
                    solved = null;
                }
            }

            if (solved == null || solved.isEmpty()) {
                // 2. Pre-process for OCR
                BufferedImage processed = preprocessImage(captchaFile);

                // 3. Identify every character first — nothing gets typed until
                //    this returns a final answer. resolveViaOcr() runs the
                //    per-character segmentation pipeline first (see class
                //    javadoc) and falls back to whole-string OCR automatically.
                solved = resolveViaOcr(processed, effectiveExpectedLength);
                if (effectiveExpectedLength > 0 && solved.length() != effectiveExpectedLength) {
                    log.warn("⚠ Solved CAPTCHA length ({}) does not match expected length ({}). " +
                            "The result might be incorrect. Solved: [{}]",
                        solved.length(), effectiveExpectedLength, solved);
                }
            }

            if (solved == null || solved.isEmpty()) {
                log.error("❌ solveTextCaptcha: could not identify any characters at all "
                        + "(AI vision" + (aiEnabled ? " and " : " disabled, ") + "OCR both tried both PSMs) "
                        + "— leaving the field untouched rather than typing a blank/garbage answer. "
                        + "Inspect the saved screenshot at {}",
                    captchaFile.getAbsolutePath());
                return null;
            }

            log.info("✅ Text CAPTCHA fully identified: [{}]", solved);

            // 4. Type into field, and re-solve once if the field itself
            //    reveals a shorter true length than we assumed (see
            //    typeIntoField()'s truncation-detection javadoc) — this is
            //    what actually happens when effectiveExpectedLength couldn't
            //    be discovered up front (no maxlength attribute AND no
            //    readable DOM property) but the field enforces one anyway.
            solved = typeWithLengthCorrection(driver, captchaFile, captchaInputField, solved,
                effectiveExpectedLength);

            return solved;

        } catch (Exception e) {
            log.error("❌ solveTextCaptcha failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Runs OCR against the pre-processed CAPTCHA image and returns the fully
     * cleaned answer — every character identified before the caller ever
     * types anything. First pass uses the constructor's PSM 7 (single text
     * line, the normal case for a CAPTCHA strip). If that comes back empty
     * after cleaning (whitelist rejected everything / OCR found nothing),
     * retries once with PSM 8 (single word) — a different segmentation mode
     * genuinely reads some CAPTCHA renderings (tightly-kerned or slightly
     * rotated character strips in particular) that PSM 7 misses entirely.
     * The PSM is restored to 7 afterward regardless of which pass succeeded,
     * since every other caller (auto-detect, future calls) expects the
     * constructor's default.
     */
    private String identifyText(BufferedImage processed) throws Exception {
        String firstPass = cleanOCRText(tesseract.doOCR(processed));
        if (!firstPass.isEmpty()) {
            return firstPass;
        }

        log.warn("⚠ First OCR pass (PSM 7) identified no characters — retrying with PSM 8 "
            + "(single word) before giving up");
        tesseract.setPageSegMode(8);
        try {
            return cleanOCRText(tesseract.doOCR(processed));
        } finally {
            tesseract.setPageSegMode(7);
        }
    }

    // =========================================================================
    // PER-CHARACTER SEGMENTATION — the actual fix for letter/digit confusion.
    // See the class-level javadoc for the overall pipeline; this section is
    // everything resolveViaOcr() and segmentedIdentify() need.
    // =========================================================================

    /**
     * Single entry point for "what's the best OCR answer we can get" once AI
     * Vision is disabled/unavailable. Runs the per-character segmentation
     * pipeline first (segmentedIdentify()) since isolating each glyph before
     * classifying it is what actually resolves confusable letter/digit
     * shapes — whole-string OCR (identifyText(), PSM 7/8) reads every
     * character with its neighbours' strokes still bleeding into the
     * recognition window, which is the single biggest cause of "most
     * characters right, a few wrong" on tightly-kerned CAPTCHA fonts.
     * Whole-string OCR is always still run too (cheap relative to
     * segmentation) and used whenever segmentation didn't produce a usable
     * result — e.g. the glyphs touch/overlap too much to isolate cleanly, or
     * too few/many components were found to be a plausible character count
     * (see captcha.segmentation.minChars/maxChars).
     */
    private String resolveViaOcr(BufferedImage processed, int knownExpectedLength) throws Exception {
        SegResult segmented = segmentedIdentify(processed, knownExpectedLength);
        String wholeString = identifyText(processed);

        if (segmented != null && !segmented.text.isEmpty()) {
            if (wholeString != null && !wholeString.isEmpty() && !wholeString.equalsIgnoreCase(segmented.text)) {
                log.debug("OCR ensemble disagreement — segmented=[{}] (avg confidence {}), whole-string=[{}]. "
                        + "Using the segmented (per-character) result: isolating each glyph is more reliable "
                        + "for confusable characters than reading them as part of a string.",
                    segmented.text, String.format("%.1f", segmented.avgConfidence), wholeString);
            } else {
                log.debug("Segmented per-character OCR read: [{}] (avg confidence {})",
                    segmented.text, String.format("%.1f", segmented.avgConfidence));
            }
            return segmented.text;
        }

        return wholeString;
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
    private SegResult segmentedIdentify(BufferedImage processed, int knownExpectedLength) {
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
     * 8-connected flood fill over the binarized (pure black/white) image,
     * returning the bounding box of every distinct ink blob — the raw
     * candidate set of "maybe a character" regions before noise filtering
     * or fragment merging.
     */
    private List<Segment> findConnectedComponents(BufferedImage binary) {
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
    private boolean isInk(BufferedImage binary, int x, int y) {
        return (binary.getRGB(x, y) & 0xFF) < 128;
    }

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

    /** Rebuilds a Segment for a sub-region of the original image, with a real (not assumed-solid) pixel count. */
    private Segment rebuildSegmentFromBinary(int x0, int y0, int x1, int y1, BufferedImage binary) {
        int pixelCount = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if (isInk(binary, x, y)) {
                    pixelCount++;
                }
            }
        }
        return new Segment(x0, y0, x1, y1, pixelCount);
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
     * NOT bicubic (unlike preprocessImage()'s initial 2x upscale of the raw
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

    /**
     * SOLVE_MATH_CAPTCHA keyword handler
     * Reads math expression (e.g. "3 + 5 = ?") from image, evaluates it,
     * and types the answer.
     *
     * @return answer string
     */
    public String solveMathCaptcha(WebDriver driver,
                                   WebElement captchaImage,
                                   WebElement captchaInputField) {
        log.info("▶ solveMathCaptcha started");
        try {
            // 1. Screenshot CAPTCHA element (with margin — see
            //    screenshotElementWithMargin() javadoc)
            File captchaFile = screenshotElementWithMargin(driver, captchaImage, "math_captcha");

            // 2. OCR with wider char set for math
            tesseract.setVariable("tessedit_char_whitelist", "0123456789+-*/=? ");
            BufferedImage processed = preprocessImage(captchaFile);
            String rawText = tesseract.doOCR(processed);

            log.info("Math CAPTCHA raw OCR: [{}]", rawText);

            // 3. Parse and evaluate expression
            String answer = evaluateMathExpression(rawText);

            log.info("✅ Math CAPTCHA answer: [{}]", answer);

            // 4. Type answer
            typeIntoField(captchaInputField, answer);

            // Reset whitelist back to the real configured text-CAPTCHA charset
            // (upper+lower+digits+symbols by default) — NOT a hardcoded
            // uppercase-only string. Resetting to an uppercase-only literal
            // here would silently reintroduce the same case-loss bug for any
            // text CAPTCHA solved later in the same session.
            tesseract.setVariable("tessedit_char_whitelist", textCaptchaCharset);

            return answer;

        } catch (Exception e) {
            log.error("❌ solveMathCaptcha failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * SOLVE_CAPTCHA_WITH_AI keyword handler.
     * Sends the CAPTCHA image to a vision-capable LLM (Anthropic Claude by
     * default) and types back whatever text it reports. Unlike solveTextCaptcha
     * this ALWAYS uses AI (ignores captcha.ai.enabled) — use this keyword
     * explicitly when you want AI regardless of the global toggle.
     *
     * Requires captcha.ai.model to be configured, plus captcha.ai.apiKey (or
     * the ANTHROPIC_API_KEY env var) unless captcha.ai.provider=ollama, which
     * needs no key; falls back to OCR (never hard-fails the test) if the API
     * call fails for any reason — missing/invalid key, network error,
     * unexpected response shape, etc.
     *
     * @return solved text
     */
    public String solveWithAI(WebDriver driver,
                              WebElement captchaImage,
                              WebElement captchaInputField) {
        log.info("▶ solveWithAI (Vision API) started");
        try {
            File captchaFile = screenshotElementWithMargin(driver, captchaImage, "ai_captcha");
            int expectedLength = resolveExpectedLength(driver, captchaInputField);

            String solved;
            try {
                solved = solveViaVision(captchaFile, expectedLength);
                log.info("✅ CAPTCHA solved via AI Vision: [{}]", solved);
            } catch (Exception aiEx) {
                log.warn("⚠ AI Vision solve failed ({}) — falling back to OCR", aiEx.getMessage());
                BufferedImage processed = preprocessImage(captchaFile);
                solved = resolveViaOcr(processed, expectedLength);
            }

            if (solved == null || solved.isEmpty()) {
                log.error("❌ solveWithAI: could not identify any characters (AI and OCR fallback both "
                        + "failed) — leaving the field untouched. Inspect the saved screenshot at {}",
                    captchaFile.getAbsolutePath());
                return null;
            }

            solved = typeWithLengthCorrection(driver, captchaFile, captchaInputField, solved, expectedLength);
            return solved;

        } catch (Exception e) {
            log.error("❌ solveWithAI failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Orchestrates a solveViaVision() call: makes the vision-API request,
     * and — when the input field's maxlength gives us a known expected
     * answer length — validates the result against it and retries ONCE with
     * an explicit correction hint if it doesn't match.
     *
     * A length mismatch is the single most reliable, checkable symptom of
     * exactly the failure modes reported against this solver: a genuine
     * character clipped at the image edge (now largely fixed by
     * screenshotElementWithMargin(), but not eliminated for CAPTCHAs whose
     * generator itself renders off-canvas) reads back short; a letter
     * misread as an extra/different character (the 'a' -> '@' case this fix
     * targets, or a whitelist-permitted symbol hallucinated where a plain
     * letter was actually rendered) can read back the right length with the
     * wrong content, which this check can't catch — that's what the
     * explicit confusable-pair warnings in the prompt itself are for — but
     * a length mismatch specifically is both detectable and often fixable
     * by simply asking the model to look again with that fact stated. Only
     * ever retries once: a vision model that's still wrong on a second,
     * differently-worded pass is unlikely to self-correct on a third
     * identical request, so this stops rather than burning API calls.
     */
    private String solveViaVision(File captchaFile, int expectedLength) throws Exception {
        String solved = resolveWithVisionApi(captchaFile, expectedLength, null);

        if (expectedLength > 0 && solved.length() != expectedLength) {
            log.warn("⚠ AI Vision CAPTCHA answer length ({}) does not match the expected length ({}) read "
                    + "from the input field's maxlength — retrying once with an explicit correction hint. "
                    + "First attempt: [{}]",
                solved.length(), expectedLength, solved);
            try {
                String retried = resolveWithVisionApi(captchaFile, expectedLength, solved);
                if (retried != null && !retried.isEmpty()) {
                    if (retried.length() == expectedLength) {
                        log.info("✅ AI Vision retry produced the expected length ({} chars) — using it: [{}]",
                            expectedLength, retried);
                    } else {
                        log.warn("⚠ AI Vision retry still doesn't match the expected length ({} vs {}) — "
                                + "using the retry's answer anyway (a second independent read is generally "
                                + "at least as reliable as the first): [{}]",
                            retried.length(), expectedLength, retried);
                    }
                    solved = retried;
                }
            } catch (Exception retryEx) {
                log.warn("⚠ AI Vision retry-for-length-mismatch call failed ({}) — keeping the first "
                        + "answer: [{}]",
                    retryEx.getMessage(), solved);
            }
        }

        return solved;
    }

    /**
     * Sends a CAPTCHA screenshot to a vision-capable LLM and returns exactly
     * the characters it reports, or throws if the call can't be completed.
     * Callers are expected to catch and fall back to OCR — this method
     * itself makes no OCR attempt. Use solveViaVision() rather than calling
     * this directly — it adds the expected-length validation/retry above.
     *
     * @param expectedLength the input field's maxlength (0 if unknown/not
     *                       set) — folded into the prompt as an explicit
     *                       character-count hint so the model self-checks
     *                       its own read instead of the framework only
     *                       finding out after the fact.
     * @param previousAttempt the prior (wrong-length) answer to reference
     *                        when this is a retry, or null for a first
     *                        attempt.
     *
     * Uses the Anthropic Messages API (api.anthropic.com/v1/messages) with
     * an image content block by default. Set captcha.ai.provider=ollama to
     * instead call a local/remote Ollama server's /api/generate endpoint
     * (e.g. a self-hosted llava model) — that provider is natively
     * supported below with its own request/response shape and needs no API
     * key. captcha.ai.endpoint/model/apiKey remain overridable via config
     * for either provider.
     */
    private String resolveWithVisionApi(File captchaFile, int expectedLength, String previousAttempt) throws Exception {
        // Ollama serves models locally/on a LAN box with no auth by default,
        // so it's the one provider that legitimately has no API key at all —
        // don't hard-fail on a blank aiApiKey for it.
        if (!isOllama && (aiApiKey == null || aiApiKey.isBlank())) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but no API key configured. "
                + "Set captcha.ai.apiKey (or the ANTHROPIC_API_KEY environment variable).");
        }
        if (aiModel == null || aiModel.isBlank()) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but captcha.ai.model is not "
                + "set. Configure it to a vision-capable model you have access to (e.g. a current Claude "
                + "or GPT-4o-class model, or a local Ollama model like llava) via captcha.ai.model.");
        }

        String base64Image = encodeImageToBase64(captchaFile);
        String mediaType = captchaFile.getName().toLowerCase().endsWith(".jpg")
            || captchaFile.getName().toLowerCase().endsWith(".jpeg") ? "image/jpeg" : "image/png";
        String promptText = buildVisionPrompt(expectedLength, previousAttempt);

        // Visible at the default INFO log level (not DEBUG) — this is the
        // line to grep for to confirm which provider/model/endpoint a given
        // solve actually went to, e.g. to tell "it silently fell back to
        // OCR" apart from "it called Ollama and got a wrong answer".
        log.info("🤖 Vision API call: provider={}, model={}, endpoint={}, image={}{}",
            aiProvider, aiModel, aiEndpoint, captchaFile.getAbsolutePath(),
            previousAttempt != null ? " (retry, previous=[" + previousAttempt + "])" : "");

        ObjectNode requestBody;
        if (isOllama) {
            // Ollama's /api/generate: flat request, image(s) as a bare
            // base64 array (no data-URI prefix, no per-image media type),
            // "stream": false so the whole answer comes back as one JSON
            // object instead of newline-delimited partial-token chunks.
            requestBody = objectMapper.createObjectNode();
            requestBody.put("model", aiModel);
            requestBody.put("prompt", promptText);
            ArrayNode images = objectMapper.createArrayNode();
            images.add(base64Image);
            requestBody.set("images", images);
            requestBody.put("stream", false);
            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", 0);
            requestBody.set("options", options);
        } else {
            ObjectNode imageSource = objectMapper.createObjectNode();
            imageSource.put("type", "base64");
            imageSource.put("media_type", mediaType);
            imageSource.put("data", base64Image);

            ObjectNode imageBlock = objectMapper.createObjectNode();
            imageBlock.put("type", "image");
            imageBlock.set("source", imageSource);

            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", promptText);

            ArrayNode content = objectMapper.createArrayNode();
            content.add(imageBlock);
            content.add(textBlock);

            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");
            message.set("content", content);

            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(message);

            requestBody = objectMapper.createObjectNode();
            requestBody.put("model", aiModel);
            requestBody.put("max_tokens", 32);
            requestBody.put("temperature", 0);
            requestBody.set("messages", messages);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(aiEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
        if (isOllama) {
            // Most local Ollama setups need no auth at all; if a key IS
            // configured anyway (e.g. an nginx reverse proxy in front of a
            // remote box adding its own auth), send it as a bearer token
            // rather than silently dropping it.
            if (aiApiKey != null && !aiApiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + aiApiKey);
            }
        } else {
            requestBuilder.header("x-api-key", aiApiKey);
            requestBuilder.header("anthropic-version", "2023-06-01");
        }
        HttpRequest request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

        // Retry transient failures (rate limit / server-side errors / a dropped
        // connection) instead of falling back to OCR on the first hiccup —
        // OCR is strictly less accurate than the Vision model on distorted
        // fonts (see class javadoc), so giving up on AI after one flaky
        // network blip throws away accuracy for no real reason. A 4xx other
        // than 429 (bad key, bad request shape) is not retried — retrying
        // won't fix it and just wastes 2 more round-trips before falling
        // back anyway.
        HttpResponse<String> response = null;
        IOException lastError = null;
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    lastError = null;
                    break;
                }
                boolean retryable = status == 429 || status >= 500;
                lastError = new IOException("Vision API returned HTTP " + status + ": " + response.body());
                if (!retryable || attempt == maxAttempts) {
                    throw lastError;
                }
                log.warn("⚠ Vision API call got HTTP {} (attempt {}/{}) — retrying",
                    status, attempt, maxAttempts);
            } catch (java.io.IOException e) {
                lastError = e;
                if (attempt == maxAttempts) {
                    throw lastError;
                }
                log.warn("⚠ Vision API call failed ({}) on attempt {}/{} — retrying",
                    e.getMessage(), attempt, maxAttempts);
            }
            try {
                Thread.sleep(500L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw lastError != null ? lastError : new IOException("Interrupted while retrying Vision API call");
            }
        }
        if (lastError != null) {
            throw lastError;
        }

        JsonNode root = objectMapper.readTree(response.body());
        StringBuilder rawAnswer = new StringBuilder();
        if (isOllama) {
            // {"model":"llava", "response":"ABC123", "done":true, ...}
            JsonNode responseNode = root.get("response");
            if (responseNode == null || responseNode.asText().isBlank()) {
                JsonNode errorNode = root.get("error");
                String detail = errorNode != null ? errorNode.asText() : response.body();
                throw new IOException("Ollama Vision response had no usable \"response\" field: " + detail);
            }
            rawAnswer.append(responseNode.asText());
        } else {
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                throw new IOException("Vision API response had no content block: " + response.body());
            }
            for (JsonNode block : contentArray) {
                JsonNode textNode = block.get("text");
                if (textNode != null) {
                    rawAnswer.append(textNode.asText());
                }
            }
        }

        // Log the model's raw, uncleaned reply at INFO — this is what the
        // model literally said before cleanOCRText() strips stray
        // whitespace/punctuation, useful for telling "the model misread the
        // image" apart from "the model read it right but cleaning mangled
        // it" when an answer comes back wrong.
        log.info("🤖 Vision API raw reply ({}): [{}]", aiProvider, rawAnswer);

        // Still route through cleanOCRText()'s charset filter — strips any
        // stray whitespace/punctuation the model might add despite the
        // instruction, without forcing case (see that method's own javadoc
        // on why case must never be forced here).
        String cleaned = cleanOCRText(rawAnswer.toString());
        if (cleaned.isEmpty()) {
            throw new IOException("Vision API returned an empty/unusable answer: [" + rawAnswer + "]");
        }
        return cleaned;
    }

    /**
     * Builds the vision-model prompt. Split out of resolveWithVisionApi()
     * so it's easy to see/tune everything the model is actually told in one
     * place, and so solveViaVision()'s retry can reuse it with a different
     * hint appended.
     *
     * Three things were added here to directly address the reported
     * failure modes (text getting cut off, case confusion, and letters
     * being read back as special characters):
     *   1. The prompt now states the CAPTCHA's actual configured character
     *      set (textCaptchaCharset) explicitly, instead of leaving the
     *      model to guess what's "in scope". Previously the model was free
     *      to output any special character it thought it saw — with
     *      nothing telling it "@" is even a possibility, seeing a rounded,
     *      slightly-open 'a' in a distorted font, it had no signal to
     *      prefer the far more common answer (a plain letter) over the
     *      visually-similar one. Naming the exact allowed characters (and
     *      naming the specific confusable pairs below) gives it that
     *      signal directly, and for sites where the real CAPTCHA alphabet
     *      is letters+digits only, setting captcha.text.charset to exclude
     *      symbols removes the "@" misread as a possible answer entirely.
     *   2. A short, explicit list of the exact confusable pairs this class
     *      already has geometric shape-tiebreak logic for on the OCR side
     *      (see CONFUSABLE_PAIRS/resolveConfusableByShape) — including the
     *      letter-vs-symbol ones ('a'/'A' vs '@', 'S'/'s' vs '5'/'$',
     *      'I'/'l'/'1' vs '!') and the case-lookalike ones (a capital that
     *      renders the same shape as its lowercase form at this CAPTCHA's
     *      font size, e.g. C/c, O/o, S/s, V/v, W/w, X/x, Z/z — where the
     *      model has to judge case from relative height/weight rather than
     *      shape, exactly like applyRelativeCaseCorrection() does for OCR).
     *   3. An explicit instruction to still commit to a single best-guess
     *      character for anything that looks partially cut off or overlaps
     *      the image edge, rather than omitting it or merging it into a
     *      neighbouring character — the model-side complement to
     *      screenshotElementWithMargin() giving it more of the actual pixels
     *      to work with in the first place.
     */
    private String buildVisionPrompt(int expectedLength, String previousAttempt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("This image is a distorted-text CAPTCHA. Reply with ONLY the exact characters "
            + "shown in the image, preserving upper/lower case exactly as rendered. No explanation, "
            + "no punctuation, no markdown, no leading/trailing whitespace — just the characters "
            + "themselves, nothing else.\n\n");

        prompt.append("Every character in this CAPTCHA is one of: ").append(textCaptchaCharset).append(". ")
            .append("Do not output any character outside that set.\n\n");

        prompt.append("Read the image very carefully, character by character, and watch specifically for "
            + "these commonly-confused pairs — decide by the actual shape in front of you, not by which "
            + "one is more common in normal text:\n"
            + "- A rounded lowercase 'a' (or capital 'A') vs the '@' symbol — a plain letter is far more "
            + "likely than '@'; only report '@' if you can clearly see its distinctive full spiral loop "
            + "wrapping all the way around, not just a closed letter counter.\n"
            + "- 'S'/'s' vs '5' vs '$' — only report '$' if you can clearly see a vertical bar or stroke "
            + "actually crossing through the curve.\n"
            + "- '1' vs 'I' vs 'l' (lowercase L) vs 'i' vs '!' — check for a dot above the stroke (i), "
            + "serifs top and bottom (I), or a stroke/dot below the vertical line (!).\n"
            + "- '0' vs 'O' vs 'o', '5' vs 'S', '6' vs 'G', '8' vs 'B', '9' vs 'g'/'q', 'u' vs 'v', "
            + "'m' vs 'n', 'c' vs 'e'.\n"
            + "- Upper vs lower case for letters that share the same basic shape at either case "
            + "(c/C, o/O, s/S, u/U, v/V, w/W, x/X, z/Z) — judge this from the character's height and "
            + "weight relative to the other characters in the same image, not from shape alone.\n\n");

        prompt.append("If any character is partially cut off, cropped, or touches the very edge of the "
            + "image, do NOT omit it and do NOT merge it with its neighbour — give your single best-guess "
            + "character for it based on whatever portion of it is visible, the same as you would for any "
            + "fully-visible character.\n\n");

        if (expectedLength > 0) {
            prompt.append("The answer is exactly ").append(expectedLength)
                .append(" characters long. Count the characters in the image before answering and make "
                    + "sure your reply has exactly that many.\n\n");
        }

        if (previousAttempt != null && !previousAttempt.isEmpty()) {
            prompt.append("A previous read of this same image returned \"").append(previousAttempt)
                .append("\" (").append(previousAttempt.length()).append(" characters), which did not match ")
                .append("the expected length above. Look again at the full width of the image, including "
                    + "both edges, and pay particular attention to whether a character was missed, an "
                    + "extra character was added, or two characters were merged into one — then give your "
                    + "corrected answer.\n\n");
        }

        return prompt.toString();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Takes a screenshot of a single WebElement and saves to target/captchas/
     */
    private File screenshotElement(WebElement element, String prefix) throws IOException {
        File dir = new File("target/captchas");
        dir.mkdirs();

        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File dest = new File(dir, prefix + "_" + timestamp + ".png");

        File src = element.getScreenshotAs(OutputType.FILE);
        FileUtils.copyFile(src, dest);

        log.debug("CAPTCHA screenshot saved to: {}", dest.getAbsolutePath());
        return dest;
    }

    // How many CSS pixels of extra margin to grab around the CAPTCHA
    // element's own bounding box before cropping — see
    // screenshotElementWithMargin() javadoc for why this exists.
    private final int screenshotMarginPx = ConfigReader.getInt("captcha.screenshot.marginPx", 8);

    /**
     * Like screenshotElement(), but captures a small margin of extra pixels
     * around the element's bounding box instead of cropping exactly to it.
     *
     * This is the actual fix for "some portion of the text is cutting off
     * in the CAPTCHA image": WebElement#getScreenshotAs() crops to precisely
     * the element's own CSS box. Real CAPTCHA renderers routinely draw
     * glyphs that visually overflow that box by a few pixels — italic
     * slant on the first/last character, a decorative flourish, antialiased
     * stroke edges, or simply a font whose rendered advance width is a
     * touch wider than the element the CSS/canvas author sized for. None of
     * that is a bug in the framework's crop math; it's real pixels the
     * browser rendered just outside the element's own rect, and a tight
     * crop throws them away before OCR/Vision ever sees them — which reads
     * back as a genuinely truncated first/last character, not a misread
     * one.
     *
     * Implementation takes a full-viewport screenshot (so we're cropping,
     * not re-rendering, avoiding a second layout/paint pass) and crops a
     * region expanded by screenshotMarginPx CSS pixels on every side,
     * clamped to the captured image's actual bounds. Element#getRect() and
     * TakesScreenshot both report/capture in the same coordinate space per
     * the W3C WebDriver spec (viewport-relative), but the captured PNG's
     * actual pixel dimensions can still be scaled relative to CSS pixels on
     * a HiDPI/Retina display (devicePixelRatio != 1) — window.devicePixelRatio
     * is read via JS and applied to both the rect and the margin before
     * cropping so this stays correct on high-DPI runs, not just 1x ones.
     * Falls back to the plain tight crop (screenshotElement) if anything
     * here fails, rather than letting a CAPTCHA solve attempt blow up on
     * what is ultimately a nice-to-have accuracy improvement.
     */
    /**
     * Polls the CAPTCHA &lt;img&gt; itself (via img.complete/naturalWidth,
     * same pattern BrokenLinksImagesPage already uses for the identical
     * problem on a different page) until it has actually finished loading,
     * up to captchaImageLoadTimeoutSeconds. presenceOfElementLocated /
     * visibilityOfElementLocated upstream only confirm the &lt;img&gt; tag
     * exists and is visible in the DOM — neither says anything about
     * whether the browser has finished downloading/decoding the image
     * itself, so screenshotting right after those checks is a race: it
     * passes when the CDN/network happens to be fast and quietly captures
     * a broken-image icon (plus its alt text, which is exactly how "solved"
     * answers like "lesCaptcha" happen — that's the alt text "Captcha"
     * getting OCR'd/vision-read, not a real solving-accuracy problem) when
     * it isn't. Throws rather than returning a boolean so the caller can't
     * accidentally proceed to screenshot/solve a known-broken image.
     */
    private void waitForCaptchaImageLoaded(WebDriver driver, WebElement imgElement) throws IOException {
        long deadline = System.currentTimeMillis()
            + java.time.Duration.ofSeconds(captchaImageLoadTimeoutSeconds).toMillis();
        Long lastNaturalWidth = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Object result = ((JavascriptExecutor) driver).executeScript(
                    "var img = arguments[0];"
                        + "if (img.complete) { return img.naturalWidth; }"
                        + "return null;",
                    imgElement);
                if (result != null) {
                    long width = (result instanceof Number) ? ((Number) result).longValue() : 0L;
                    if (width > 0) {
                        return;
                    }
                    // .complete==true with naturalWidth==0 means the browser
                    // is DONE trying (successfully or not) — a real broken
                    // image, not "still loading". No point burning the rest
                    // of the timeout polling something that's already
                    // final; fail fast with a clear message instead.
                    lastNaturalWidth = width;
                    break;
                }
            } catch (StaleElementReferenceException staleEx) {
                throw new IOException("[CaptchaSolver] CAPTCHA image element went stale while waiting for it "
                    + "to load — the page likely re-rendered the CAPTCHA out from under this call. "
                    + "Re-locate the element and retry.", staleEx);
            } catch (Exception jsEx) {
                log.debug("Could not poll CAPTCHA image load state ({}) — retrying", jsEx.getMessage());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("[CaptchaSolver] Interrupted while waiting for the CAPTCHA image to load");
            }
        }
        throw new IOException("[CaptchaSolver] CAPTCHA image never finished loading within "
            + captchaImageLoadTimeoutSeconds + "s (naturalWidth stayed "
            + (lastNaturalWidth != null ? lastNaturalWidth : "unresolved") + ") — screenshotting/solving it now "
            + "would just read a broken-image placeholder (icon + alt text), not an actual CAPTCHA. This "
            + "usually means the page's own JS renders the CAPTCHA image asynchronously, later than "
            + "document.readyState=='complete' — increase captcha.image.load.timeout if the CAPTCHA is just "
            + "slow to render, or check the page/network if it never loads at all.");
    }

    private File screenshotElementWithMargin(WebDriver driver, WebElement element, String prefix) throws IOException {
        waitForCaptchaImageLoaded(driver, element);
        try {
            Rectangle rect = element.getRect();

            double devicePixelRatio = 1.0;
            try {
                Object raw = ((JavascriptExecutor) driver).executeScript("return window.devicePixelRatio;");
                if (raw instanceof Number) {
                    devicePixelRatio = ((Number) raw).doubleValue();
                }
            } catch (Exception dprEx) {
                log.debug("Could not read window.devicePixelRatio ({}); assuming 1.0", dprEx.getMessage());
            }

            File fullShot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            BufferedImage full = ImageIO.read(fullShot);
            if (full == null) {
                throw new IOException("Full-viewport screenshot could not be decoded");
            }

            int marginPxScaled = (int) Math.round(screenshotMarginPx * devicePixelRatio);
            int x0 = (int) Math.floor(rect.getX() * devicePixelRatio) - marginPxScaled;
            int y0 = (int) Math.floor(rect.getY() * devicePixelRatio) - marginPxScaled;
            int x1 = (int) Math.ceil((rect.getX() + rect.getWidth()) * devicePixelRatio) + marginPxScaled;
            int y1 = (int) Math.ceil((rect.getY() + rect.getHeight()) * devicePixelRatio) + marginPxScaled;

            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            x1 = Math.min(full.getWidth(), x1);
            y1 = Math.min(full.getHeight(), y1);

            int w = x1 - x0;
            int h = y1 - y0;
            if (w <= 0 || h <= 0) {
                throw new IOException("Computed crop region was empty (element rect: " + rect
                    + ", devicePixelRatio: " + devicePixelRatio + ")");
            }

            BufferedImage cropped = full.getSubimage(x0, y0, w, h);

            File dir = new File("target/captchas");
            dir.mkdirs();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File dest = new File(dir, prefix + "_" + timestamp + ".png");
            ImageIO.write(cropped, "png", dest);

            // INFO, not DEBUG — this is the exact image file that gets sent
            // to OCR/the Vision API; open it directly to see what the model
            // actually saw when checking a wrong answer.
            log.info("📸 CAPTCHA screenshot (with {}px margin, {}x device pixel ratio) saved to: {}",
                screenshotMarginPx, devicePixelRatio, dest.getAbsolutePath());
            return dest;
        } catch (Exception e) {
            log.warn("⚠ Margin-padded CAPTCHA screenshot failed ({}) — falling back to a tight element "
                    + "crop; edge characters may be more likely to appear cut off",
                e.getMessage());
            return screenshotElement(element, prefix);
        }
    }

    /**
     * Grayscale + threshold pre-processing for better OCR accuracy
     */
    private BufferedImage preprocessImage(File imageFile) throws IOException {
        BufferedImage original = ImageIO.read(imageFile);

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
     * Remove noise chars from OCR output.
     *
     * IMPORTANT: this must NOT force-uppercase the result. The constructor
     * already whitelists Tesseract to upper+lower+digits+symbols via
     * textCaptchaCharset specifically so mixed-case CAPTCHAs (e.g. the
     * india.ai site, which renders a genuine mix of upper and lower case
     * letters) can be read correctly — forcing .toUpperCase() here silently
     * threw that case information away right after OCR, which is why every
     * answer still came out as all-caps even though Tesseract itself was
     * reading the correct characters. Only strip characters OUTSIDE the
     * configured charset (not a hardcoded a-zA-Z0-9), so any site-specific
     * symbols added via captcha.text.charset survive cleaning too.
     */
    private String cleanOCRText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (textCaptchaCharset.indexOf(c) >= 0) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    /**
     * Evaluates simple math expressions like "3 + 5", "12 - 4", "6 * 2"
     */
    private String evaluateMathExpression(String rawText) {
        // Normalize OCR artifacts
        String expr = rawText
            .replaceAll("[^0-9+\\-*/]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        log.debug("Evaluating math expression: [{}]", expr);

        try {
            String[] parts;
            if (expr.contains("+")) {
                parts = expr.split("\\+");
                return String.valueOf(Integer.parseInt(parts[0].trim())
                    + Integer.parseInt(parts[1].trim()));
            } else if (expr.contains("-")) {
                parts = expr.split("-");
                return String.valueOf(Integer.parseInt(parts[0].trim())
                    - Integer.parseInt(parts[1].trim()));
            } else if (expr.contains("*")) {
                parts = expr.split("\\*");
                return String.valueOf(Integer.parseInt(parts[0].trim())
                    * Integer.parseInt(parts[1].trim()));
            } else if (expr.contains("/")) {
                parts = expr.split("/");
                return String.valueOf(Integer.parseInt(parts[0].trim())
                    / Integer.parseInt(parts[1].trim()));
            }
        } catch (NumberFormatException | ArithmeticException e) {
            log.error("Math evaluation failed for [{}]: {}", expr, e.getMessage());
        }

        return "0"; // safe default
    }

    /**
     * Encode image file to Base64 string (needed for AI Vision APIs)
     */
    private String encodeImageToBase64(File imageFile) throws IOException {
        byte[] bytes = FileUtils.readFileToByteArray(imageFile);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Clear + type text into a WebElement, then verify the field actually
     * holds what was typed and retry once if not.
     *
     * A correctly-solved CAPTCHA that never actually lands in the input is
     * indistinguishable from a wrong solve at the assertion/submit step —
     * this framework has already hit this exact failure mode elsewhere
     * (DatePickerPage.selectDateTime()'s react-datepicker input: plain
     * clear() doesn't work on some JS-controlled/masked inputs, so a typed
     * value gets silently appended after stale content, or dropped
     * entirely, instead of replacing it). Applying the same
     * verify-and-retry pattern here closes that gap for CAPTCHA fields too.
     *
     * IMPORTANT distinction added here: a mismatch after typing has two
     * genuinely different causes that need different handling, and this
     * previously treated both as the same "dropped clear()/sendKeys()"
     * problem and retried with the identical text either way:
     *   1. Real dropped-keystroke/stale-content weirdness (the
     *      DatePickerPage precedent above) — retrying with select-all +
     *      delete + re-type can actually fix this.
     *   2. The field enforcing a shorter length via its own JS input
     *      handler than resolveExpectedLength() was able to discover
     *      up front (no maxlength attribute AND the DOM maxLength property
     *      wasn't set either — seen for real against india.ai's staging
     *      CAPTCHA field). Here `actual` is a plain PREFIX of `text` — the
     *      first N characters typed exactly as sent, then every keystroke
     *      after that silently rejected. Retrying with the exact same
     *      (too-long) text is guaranteed to reproduce the exact same
     *      truncated result — it was never a dropped-keystroke problem, so
     *      that retry was pure noise. Detecting this case and returning the
     *      field's real length instead lets the caller re-solve the CAPTCHA
     *      against the now-known-correct length (see
     *      typeWithLengthCorrection()) rather than uselessly resubmitting
     *      the same wrong text a second time.
     *
     * @return the field's true length as discovered by truncation (case 2
     *         above) if that's what happened, or -1 if the type either
     *         succeeded or failed for some other reason (case 1, or an
     *         unreadable field).
     */
    private int typeIntoField(WebElement field, String text) {
        field.clear();
        field.sendKeys(text);

        String actual = safeGetFieldValue(field);
        if (!text.equals(actual)) {
            if (isPlainPrefixTruncation(actual, text)) {
                log.warn("⚠ CAPTCHA input field truncated the typed answer to [{}] ({} of {} characters "
                        + "sent) — this is the field enforcing its own shorter length via JS, not a "
                        + "dropped-keystroke issue, so retyping the same text would just reproduce the "
                        + "same truncation. Reporting the discovered length back to the caller instead.",
                    actual, actual.length(), text.length());
                log.info("Typed CAPTCHA answer into field (truncated): [{}]", actual);
                return actual.length();
            }

            log.warn("⚠ CAPTCHA input field reads [{}] after typing, expected [{}] — likely a "
                    + "JS-controlled input that dropped clear()/sendKeys(). Retrying with "
                    + "select-all + delete + re-type.",
                actual, text);
            field.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.DELETE);
            field.sendKeys(text);
            actual = safeGetFieldValue(field);
            if (!text.equals(actual)) {
                if (isPlainPrefixTruncation(actual, text)) {
                    log.warn("⚠ CAPTCHA input field truncated the typed answer to [{}] ({} of {} characters "
                            + "sent) on the retry too — confirms this is a real length cap, not a dropped "
                            + "keystroke. Reporting the discovered length back to the caller.",
                        actual, actual.length(), text.length());
                    log.info("Typed CAPTCHA answer into field (truncated): [{}]", actual);
                    return actual.length();
                }
                log.warn("⚠ CAPTCHA input field still reads [{}] after retry (expected [{}]) — "
                        + "the solved answer may not have taken; a downstream submit/assert "
                        + "failure on this field is not necessarily a bad OCR/AI read.",
                    actual, text);
            }
        }

        log.info("Typed CAPTCHA answer into field: [{}]", text);
        return -1;
    }

    /** True if `actual` is exactly the first N characters of `text`, N < text.length(), and non-empty. */
    private boolean isPlainPrefixTruncation(String actual, String text) {
        return actual != null && !actual.isEmpty()
            && actual.length() < text.length()
            && text.startsWith(actual);
    }

    /**
     * Types the solved answer into the field, and — if the field itself
     * reveals a shorter true length than the caller assumed (see
     * typeIntoField()'s truncation-detection javadoc) — re-solves the
     * CAPTCHA exactly once against that now-known-correct length and
     * re-types the corrected answer, instead of leaving the field holding
     * a truncated fragment of an over-long, wrong first guess.
     *
     * Only fires when resolveExpectedLength() couldn't discover the real
     * length up front (both the maxlength attribute AND the DOM maxLength
     * property were unavailable) — when it did, the length is already
     * folded into the original solve/prompt and this is a no-op path.
     */
    private String typeWithLengthCorrection(WebDriver driver, File captchaFile, WebElement captchaInputField,
                                            String solved, int assumedExpectedLength) throws Exception {
        int discoveredLength = typeIntoField(captchaInputField, solved);

        if (discoveredLength > 0 && discoveredLength != assumedExpectedLength && discoveredLength < solved.length()) {
            log.warn("⚠ Re-solving this CAPTCHA once against the field's real length ({} characters, "
                    + "discovered from truncation — assumed length was {}) instead of submitting the "
                    + "truncated fragment of the original {}-character guess.",
                discoveredLength, assumedExpectedLength > 0 ? String.valueOf(assumedExpectedLength) : "unknown",
                solved.length());

            String corrected = null;
            if (aiEnabled) {
                try {
                    corrected = solveViaVision(captchaFile, discoveredLength);
                } catch (Exception aiEx) {
                    log.warn("⚠ AI Vision re-solve at the corrected length failed ({}) — falling back to OCR",
                        aiEx.getMessage());
                }
            }
            if (corrected == null || corrected.isEmpty()) {
                BufferedImage processed = preprocessImage(captchaFile);
                corrected = resolveViaOcr(processed, discoveredLength);
            }

            if (corrected != null && !corrected.isEmpty() && !corrected.equals(solved)) {
                log.info("✅ Re-solved CAPTCHA at the corrected length: [{}] (was: [{}])", corrected, solved);
                typeIntoField(captchaInputField, corrected);
                return corrected;
            }
            log.warn("⚠ Re-solve at the corrected length didn't produce a different/usable answer — the "
                    + "field is left holding the truncated fragment of the original guess: [{}]",
                solved.length() > discoveredLength ? solved.substring(0, discoveredLength) : solved);
        }

        return solved;
    }

    private String safeGetFieldValue(WebElement field) {
        try {
            return field.getAttribute("value");
        } catch (Exception e) {
            return null;
        }
    }
}
