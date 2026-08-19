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
    private final String aiApiKey = ConfigReader.get("captcha.ai.apiKey",
        System.getenv().getOrDefault("ANTHROPIC_API_KEY", ""));
    private final String aiModel = ConfigReader.get("captcha.ai.model", "");
    private final String aiEndpoint = ConfigReader.get("captcha.ai.endpoint", "https://api.anthropic.com/v1/messages");

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

    // -----------------------------------------------------------------------
    // Additional preprocessing toggles (see preprocessImage()). Both default
    // on — they're pure image-processing passes that no-op harmlessly on a
    // CAPTCHA that doesn't need them (an already-straight image won't find a
    // better deskew angle than 0; an image with no long straight decorative
    // line won't have anything for removeLongThinLines() to erase).
    // -----------------------------------------------------------------------
    private final boolean deskewEnabled = ConfigReader.getBoolean("captcha.preprocessing.deskew.enabled", true);
    private final boolean lineRemovalEnabled = ConfigReader.getBoolean("captcha.preprocessing.lineRemoval.enabled", true);

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

        Segment(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
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
            List<Segment> denoised = filterNoiseComponents(raw);
            List<Segment> merged = mergeFragments(denoised);
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
                visited[x][y] = true;
                stack.push(new int[]{x, y});
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
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
                components.add(new Segment(minX, minY, maxX + 1, maxY + 1));
            }
        }
        return components;
    }

    /** True if this pixel is "ink" (foreground/character stroke) in the binarized black-on-white image. */
    private boolean isInk(BufferedImage binary, int x, int y) {
        return (binary.getRGB(x, y) & 0xFF) < 128;
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
                            Math.max(a.x1, b.x1), Math.max(a.y1, b.y1));
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

    /** Crops one component out of the full image with a white padding margin, ready for per-character OCR. */
    private BufferedImage cropWithPadding(BufferedImage src, Segment seg, int padding) {
        int w = src.getWidth();
        int h = src.getHeight();
        int x0 = Math.max(0, seg.x0 - padding);
        int y0 = Math.max(0, seg.y0 - padding);
        int x1 = Math.min(w, seg.x1 + padding);
        int y1 = Math.min(h, seg.y1 + padding);
        int cw = Math.max(1, x1 - x0);
        int ch = Math.max(1, y1 - y0);

        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, cw, ch);
        g.drawImage(src, 0, 0, cw, ch, x0, y0, x1, y1, null);
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
     * Requires captcha.ai.apiKey (or the ANTHROPIC_API_KEY env var) and
     * captcha.ai.model to be configured; falls back to OCR (never hard-fails
     * the test) if the API call fails for any reason — missing/invalid key,
     * network error, unexpected response shape, etc.
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
     * an image content block, by default — captcha.ai.endpoint/model/apiKey
     * are all overridable via config if you want to point this at a
     * different vision-capable provider's OpenAI-compatible endpoint
     * instead (adjust the request/response shape below if so; this method
     * assumes Anthropic's request/response JSON shape as written).
     */
    private String resolveWithVisionApi(File captchaFile, int expectedLength, String previousAttempt) throws Exception {
        if (aiApiKey == null || aiApiKey.isBlank()) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but no API key configured. "
                + "Set captcha.ai.apiKey (or the ANTHROPIC_API_KEY environment variable).");
        }
        if (aiModel == null || aiModel.isBlank()) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but captcha.ai.model is not "
                + "set. Configure it to a vision-capable model you have access to (e.g. a current Claude "
                + "or GPT-4o-class model) via captcha.ai.model.");
        }

        String base64Image = encodeImageToBase64(captchaFile);
        String mediaType = captchaFile.getName().toLowerCase().endsWith(".jpg")
            || captchaFile.getName().toLowerCase().endsWith(".jpeg") ? "image/jpeg" : "image/png";

        ObjectNode imageSource = objectMapper.createObjectNode();
        imageSource.put("type", "base64");
        imageSource.put("media_type", mediaType);
        imageSource.put("data", base64Image);

        ObjectNode imageBlock = objectMapper.createObjectNode();
        imageBlock.put("type", "image");
        imageBlock.set("source", imageSource);

        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", buildVisionPrompt(expectedLength, previousAttempt));

        ArrayNode content = objectMapper.createArrayNode();
        content.add(imageBlock);
        content.add(textBlock);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", aiModel);
        requestBody.put("max_tokens", 32);
        requestBody.put("temperature", 0);
        requestBody.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(aiEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("x-api-key", aiApiKey)
            .header("anthropic-version", "2023-06-01")
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
        JsonNode contentArray = root.get("content");
        if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
            throw new IOException("Vision API response had no content block: " + response.body());
        }

        StringBuilder rawAnswer = new StringBuilder();
        for (JsonNode block : contentArray) {
            JsonNode textNode = block.get("text");
            if (textNode != null) {
                rawAnswer.append(textNode.asText());
            }
        }

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
    private File screenshotElementWithMargin(WebDriver driver, WebElement element, String prefix) throws IOException {
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

            log.debug("CAPTCHA screenshot (with {}px margin, {}x device pixel ratio) saved to: {}",
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

        // 2. Convert to grayscale
        BufferedImage gray = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.drawImage(scaled, 0, 0, null);
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

        // 5. Erase long, thin decorative strike-through/underline lines some
        //    CAPTCHA generators draw across (or near) the text specifically
        //    to defeat OCR. These are detected as their own connected
        //    component (only when they don't actually touch a character
        //    stroke, which is the case they'd be most likely to survive as
        //    a separate component from anyway — see removeLongThinLines()
        //    javadoc for the honest limitation here).
        BufferedImage lineFree = lineRemovalEnabled ? removeLongThinLines(binarized) : binarized;

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
