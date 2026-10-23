package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 *                                see CaptchaVisionClient), always used
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
 * CaptchaImagePreprocessor.preprocess() itself also gained contrast normalization
 * (normalizeContrast), long thin decorative-line removal
 * (removeLongThinLines), and skew correction (deskew) ahead of the existing
 * median-filter denoise + Otsu threshold — all of which help segmentation
 * produce cleaner, more separable character blobs, not just whole-string OCR.
 */
public class CaptchaSolver {

    private static final Logger log = LoggerFactory.getLogger(CaptchaSolver.class);
    private final ITesseract tesseract;
    // Image pre-processing and the AI Vision call live in their own classes
    // (same package) - this class keeps orchestration, OCR and segmentation.
    private final CaptchaImagePreprocessor preprocessor = new CaptchaImagePreprocessor();
    private final CaptchaVisionClient visionClient;
    private final CaptchaSegmenter segmenter;
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
    // Master switch: true (default) = solve automatically via AI Vision/OCR
    // as before. false = don't touch the OCR/AI pipeline at all - pause and
    // wait for a human tester to read the CAPTCHA off the real browser
    // window and type it in themselves (see waitForManualCaptchaEntry()).
    // Use this per-site (e.g. SAHMAT.properties) when the automated solver
    // isn't accurate enough for that site's CAPTCHA font yet.
    private final boolean automationEnabled = ConfigReader.getBoolean("captcha.automation.enabled", true);

    // How long to wait for the manual entry above before giving up and
    // continuing the test with whatever (if anything) is in the field.
    private final int manualEntryTimeoutSeconds = ConfigReader.getInt("captcha.manualEntry.timeoutSeconds", 120);

    private final boolean aiEnabled = ConfigReader.getBoolean("captcha.ai.enabled", false);
    // When AI Vision is used, also run the classical OCR pipeline alongside it
    // (not just as a fallback when AI fails) and reconcile the two answers —
    // see resolveTextCaptchaAnswer()/reconcileAiAndOcrAnswers(). Two
    // independently-implemented readers agreeing is much stronger evidence
    // than either alone, especially against a CAPTCHA style/font this
    // deployment hasn't been tuned against before, and a disagreement
    // between them is itself a useful, loggable signal. Set to false to
    // skip the extra OCR pass whenever AI Vision alone already succeeds
    // (matches the old "AI first, OCR only as a fallback" behavior, trading
    // away the cross-check for a little less latency/CPU per CAPTCHA).
    private final boolean aiCrossCheckWithOcr = ConfigReader.getBoolean("captcha.ai.crossCheckWithOcr", true);

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
        visionClient = new CaptchaVisionClient(textCaptchaCharset, this::cleanOCRText);
        segmenter = new CaptchaSegmenter(tesseract, preprocessor, textCaptchaCharset);
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
     * (see CaptchaVisionClient) FIRST and only falls back to Tesseract
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
    /**
     * Shared by solveTextCaptcha() and solveWithAI(): resolves a single
     * CAPTCHA screenshot to a final answer string, optionally attempting AI
     * Vision and always attempting OCR when AI doesn't produce a usable
     * answer on its own — and, when aiCrossCheckWithOcr is true (the
     * default) and AI DID succeed, running OCR anyway as an independent
     * cross-check rather than skipping it. See reconcileAiAndOcrAnswers()
     * for how the two are combined when both are present.
     *
     * @param attemptAi whether to try AI Vision at all for this call — the
     *                  two callers differ here: solveTextCaptcha() only
     *                  attempts it when captcha.ai.enabled=true, while
     *                  solveWithAI() (the explicit SOLVE_CAPTCHA_WITH_AI
     *                  keyword) always attempts it regardless of that flag.
     * @return the final answer, or null if neither AI nor OCR produced one.
     */
    private String resolveTextCaptchaAnswer(File captchaFile, int expectedLength, boolean attemptAi) {
        String aiAnswer = null;

        if (attemptAi) {
            try {
                aiAnswer = solveViaVision(captchaFile, expectedLength);
                log.info("✅ Text CAPTCHA solved via AI Vision: [{}]", aiAnswer);
            } catch (Exception aiEx) {
                log.warn("⚠ AI Vision solve failed ({}) — falling back to OCR for this CAPTCHA",
                    aiEx.getMessage());
            }
        }

        String ocrAnswer = null;
        boolean haveAiAnswer = aiAnswer != null && !aiAnswer.isEmpty();
        if (!haveAiAnswer || aiCrossCheckWithOcr) {
            try {
                BufferedImage processed = preprocessor.preprocess(captchaFile);
                ocrAnswer = resolveViaOcr(processed, expectedLength);
            } catch (Exception ocrEx) {
                log.debug("OCR read failed/unavailable ({}) — {}", ocrEx.getMessage(),
                    haveAiAnswer ? "continuing with the AI Vision answer alone"
                        : "no answer available from either path");
            }
        }

        return reconcileAiAndOcrAnswers(aiAnswer, ocrAnswer, expectedLength, captchaFile);
    }

    /**
     * Combines an AI Vision answer and an OCR answer for the same CAPTCHA
     * into one final answer, logging enough detail to actually measure
     * agreement over time as this solver is pointed at new sites/CAPTCHA
     * styles it hasn't been tuned against before.
     *
     * Preference order:
     *   1. If only one of the two is present/non-empty, use it — nothing to
     *      reconcile.
     *   2. If a known expected length is available and exactly ONE answer
     *      matches it, prefer that one regardless of provider — a length
     *      mismatch is a strong, cheap signal of a bad read (the same
     *      principle solveViaVision()'s own length-mismatch retry already
     *      relies on).
     *   3. If both answers are identical, use it — two independently
     *      implemented readers agreeing is the strongest evidence available.
     *   4. If they agree case-insensitively but differ in case, prefer the
     *      AI Vision answer — it's already been through pixel-height case
     *      correction (applyRelativeCaseCorrectionToVisionAnswer(), the
     *      same logic OCR itself uses), so log the residual case-only
     *      disagreement for visibility without changing which provider wins.
     *   5. A genuine character-level disagreement is logged at WARN with
     *      both readings and the saved image path for manual review, and
     *      the AI Vision answer is kept (this class's established "AI
     *      Vision first" preference) — but now VISIBLY: previously, once
     *      AI Vision succeeded, OCR never even ran, so a disagreement like
     *      this couldn't be seen or measured at all.
     */
    private String reconcileAiAndOcrAnswers(String aiAnswer, String ocrAnswer, int expectedLength,
                                            File captchaFile) {
        boolean haveAi = aiAnswer != null && !aiAnswer.isEmpty();
        boolean haveOcr = ocrAnswer != null && !ocrAnswer.isEmpty();

        if (!haveAi && !haveOcr) {
            return null;
        }
        if (!haveOcr) {
            return aiAnswer;
        }
        if (!haveAi) {
            if (expectedLength > 0 && ocrAnswer.length() != expectedLength) {
                log.warn("⚠ Solved CAPTCHA length ({}) does not match expected length ({}). "
                        + "The result might be incorrect. Solved: [{}]",
                    ocrAnswer.length(), expectedLength, ocrAnswer);
            }
            return ocrAnswer;
        }

        // Both are present from here on.
        boolean aiMatchesLength = expectedLength <= 0 || aiAnswer.length() == expectedLength;
        boolean ocrMatchesLength = expectedLength <= 0 || ocrAnswer.length() == expectedLength;

        if (aiMatchesLength && !ocrMatchesLength) {
            log.info("🔀 AI Vision/OCR cross-check: only the AI Vision answer matches the expected "
                + "length ({}) — using it. AI=[{}], OCR=[{}]", expectedLength, aiAnswer, ocrAnswer);
            return aiAnswer;
        }
        if (ocrMatchesLength && !aiMatchesLength) {
            log.info("🔀 AI Vision/OCR cross-check: only the OCR answer matches the expected length "
                    + "({}) — using it despite the usual AI-first preference. AI=[{}], OCR=[{}]",
                expectedLength, aiAnswer, ocrAnswer);
            return ocrAnswer;
        }

        if (aiAnswer.equals(ocrAnswer)) {
            log.info("✅ AI Vision/OCR cross-check: both readers agree exactly: [{}]", aiAnswer);
            return aiAnswer;
        }

        if (aiAnswer.equalsIgnoreCase(ocrAnswer)) {
            log.debug("AI Vision/OCR cross-check: readers agree except for case (AI=[{}], OCR=[{}]) "
                    + "— using the AI Vision answer (already pixel-height case-corrected).",
                aiAnswer, ocrAnswer);
            return aiAnswer;
        }

        log.warn("⚠ AI Vision/OCR cross-check DISAGREEMENT — AI=[{}], OCR=[{}]. Using the AI Vision "
                + "answer (established preference) but this disagreement is worth reviewing manually "
                + "— saved screenshot: {}",
            aiAnswer, ocrAnswer, captchaFile.getAbsolutePath());
        return aiAnswer;
    }

    public String solveTextCaptcha(WebDriver driver,
                                   WebElement captchaImage,
                                   WebElement captchaInputField) {
        log.info("▶ solveTextCaptcha started");

        if (!automationEnabled) {
            // Manual mode: skip AI Vision/OCR entirely and let a human
            // enter the CAPTCHA in the real browser window instead.
            return waitForManualCaptchaEntry(driver, captchaInputField);
        }

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

            // Resolves via AI Vision (only if captcha.ai.enabled=true), OCR, or
            // both — when both run, resolveTextCaptchaAnswer() cross-checks
            // them against each other rather than blindly trusting whichever
            // ran first. See its javadoc and reconcileAiAndOcrAnswers().
            String solved = resolveTextCaptchaAnswer(captchaFile, effectiveExpectedLength, aiEnabled);

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
     * Manual-entry mode (captcha.automation.enabled=false). Instead of
     * running AI Vision/OCR, this pauses the test here and waits for a
     * human tester to read the CAPTCHA off the actual browser window
     * (the driver must be running non-headless for this to be usable) and
     * type the answer into {@code captchaInputField} by hand.
     *
     * Deliberately polls the input field's own value via WebDriverWait
     * instead of blocking on System.in/console input — that keeps this
     * safe to leave wired into a suite that might occasionally run
     * headless/in CI: if nobody is watching the browser, this simply times
     * out after captcha.manualEntry.timeoutSeconds and the test continues
     * (same soft-failure shape as the OCR path returning null), rather
     * than hanging a whole pipeline forever waiting for console input that
     * will never come.
     *
     * Two things make this robust against firing on something the tester
     * never actually typed (observed in practice: the wait was satisfied
     * within ~3s on a single stray character, "n" — almost certainly a
     * leftover value or a browser-autofill flicker, not a real answer):
     *   1. The field is cleared up front, so any pre-existing/autofilled
     *      value can't immediately satisfy the wait.
     *   2. The completion check is length-aware: if the expected answer
     *      length is known (captcha.expected.length, or the field's own
     *      maxlength/DOM maxLength — see resolveExpectedLength()), the wait
     *      holds until that many characters are present, not just "non-
     *      empty". If the length genuinely isn't known, it instead requires
     *      the same value to show up on two consecutive polls (~400ms
     *      apart) before accepting it, so a mid-typing snapshot can't be
     *      mistaken for a finished entry.
     *
     * @return whatever was typed into the field, or null if nothing was
     *         entered before the timeout
     */
    private String waitForManualCaptchaEntry(WebDriver driver, WebElement captchaInputField) {
        // Try to raise the actual OS-level browser window first. This is a
        // real limitation, not something click()/focus() can fix: those
        // only set focus *inside* the page's DOM, but if a freshly-spawned
        // browser window (a new one is launched per test case) never
        // became the OS-focused window in the first place, real keystrokes
        // from the tester's physical keyboard go to whatever window (e.g.
        // the terminal running mvn) the window manager still has focused —
        // no amount of in-page focus() changes that. switchTo() on the
        // driver's own window handle is the one WebDriver call that
        // actually asks the browser/OS to raise the window, and does help
        // on many window managers, but it isn't guaranteed everywhere
        // (some Linux WMs ignore raise requests from background
        // processes) — see the log message below for the manual fallback.
        try {
            driver.switchTo().window(driver.getWindowHandle());
        } catch (Exception e) {
            log.debug("[CaptchaSolver] Could not switch to/raise the browser window before manual "
                + "entry (non-fatal): {}", e.getMessage());
        }
        // Get real, reliable keyboard focus onto the field before waiting.
        // A plain click() is enough in Chrome but is a known Firefox
        // WebDriver quirk: it doesn't always transfer true native keyboard
        // focus, so a tester's keystrokes can silently go nowhere while
        // this wait times out. Layer scrollIntoView + click + an explicit
        // JS focus() so at least one of them lands regardless of browser.
        try {
            ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'center', inline:'center'});", captchaInputField);
        } catch (Exception e) {
            log.debug("[CaptchaSolver] Could not scroll the CAPTCHA field into view before manual "
                + "entry (non-fatal): {}", e.getMessage());
        }
        try {
            captchaInputField.click();
        } catch (Exception e) {
            log.debug("[CaptchaSolver] click() on the CAPTCHA field before manual entry failed "
                + "(non-fatal, falling back to a JS focus()): {}", e.getMessage());
        }
        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].focus();", captchaInputField);
        } catch (Exception e) {
            log.debug("[CaptchaSolver] JS focus() on the CAPTCHA field before manual entry failed "
                + "(non-fatal): {}", e.getMessage());
        }
        try {
            captchaInputField.clear();
        } catch (Exception e) {
            log.debug("[CaptchaSolver] Could not clear the CAPTCHA field before manual entry "
                + "(non-fatal): {}", e.getMessage());
        }

        int expectedLength = resolveExpectedLength(driver, captchaInputField);
        log.info("✋ CAPTCHA automation is disabled (captcha.automation.enabled=false) — "
                + "please read the CAPTCHA in the browser window and type it into the field "
                + "yourself. Waiting up to {}s{}... "
                + "NOTE: a fresh browser window launches for every test case — if your keystrokes "
                + "aren't landing in the field, the window likely doesn't have real OS keyboard "
                + "focus yet (WebDriver can't force this on every window manager); click once on "
                + "the browser window itself, then type.", manualEntryTimeoutSeconds,
            expectedLength > 0 ? " for " + expectedLength + " character(s)" : "");

        java.util.concurrent.atomic.AtomicReference<String> lastSeen =
            new java.util.concurrent.atomic.AtomicReference<>("");

        try {
            new WebDriverWait(driver, Duration.ofSeconds(manualEntryTimeoutSeconds))
                .pollingEvery(Duration.ofMillis(400))
                .until(d -> {
                    String value;
                    try {
                        value = captchaInputField.getAttribute("value");
                    } catch (StaleElementReferenceException e) {
                        return false;
                    }
                    value = value == null ? "" : value.trim();
                    if (value.isEmpty()) {
                        lastSeen.set("");
                        return false;
                    }
                    if (expectedLength > 0) {
                        return value.length() >= expectedLength;
                    }
                    boolean stableAcrossTwoPolls = value.equals(lastSeen.get());
                    lastSeen.set(value);
                    return stableAcrossTwoPolls;
                });
            String typed = captchaInputField.getAttribute("value");
            log.info("✅ Manual CAPTCHA entry detected: [{}]", typed);
            return typed;
        } catch (TimeoutException e) {
            log.warn("⚠ No complete manual CAPTCHA entry detected within {}s — continuing with "
                + "whatever (if anything) is currently in the field", manualEntryTimeoutSeconds);
            try {
                return captchaInputField.getAttribute("value");
            } catch (Exception ex) {
                return null;
            }
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
        CaptchaSegmenter.SegResult segmented = segmenter.segmentedIdentify(processed, knownExpectedLength);
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
            BufferedImage processed = preprocessor.preprocess(captchaFile);
            String rawText = tesseract.doOCR(processed);

            log.info("Math CAPTCHA raw OCR: [{}]", rawText);

            // 3. Parse and evaluate expression
            String answer = evaluateMathExpression(rawText);
            if (answer == null) {
                log.error("❌ Math CAPTCHA: could not parse an expression from OCR text [{}] — "
                    + "leaving the field untouched rather than typing a made-up answer.", rawText);
                return null;
            }

            log.info("✅ Math CAPTCHA answer: [{}]", answer);

            // 4. Type answer
            typeIntoField(captchaInputField, answer);
            return answer;

        } catch (Exception e) {
            log.error("❌ solveMathCaptcha failed: {}", e.getMessage(), e);
            return null;
        } finally {
            // Reset whitelist back to the real configured text-CAPTCHA charset
            // (upper+lower+digits+symbols by default) — NOT a hardcoded
            // uppercase-only string, which would silently reintroduce the
            // case-loss bug for any text CAPTCHA solved later in the same
            // session. In a finally block (it used to sit at the end of the
            // try): this instance is reused for every later CAPTCHA on the
            // same page/keyword engine, so any exception above (OCR failure,
            // unparseable expression, typing error) used to skip the reset
            // and leave every subsequent text CAPTCHA restricted to
            // digits/math symbols — wrong answers with no error pointing here.
            tesseract.setVariable("tessedit_char_whitelist", textCaptchaCharset);
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

            // attemptAi=true unconditionally: SOLVE_CAPTCHA_WITH_AI always uses
            // AI Vision regardless of captcha.ai.enabled (see class javadoc) —
            // OCR still runs alongside/as fallback per resolveTextCaptchaAnswer().
            String solved = resolveTextCaptchaAnswer(captchaFile, expectedLength, true);

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
        String solved = visionClient.resolve(captchaFile, expectedLength, null);

        if (expectedLength > 0 && solved.length() != expectedLength) {
            log.warn("⚠ AI Vision CAPTCHA answer length ({}) does not match the expected length ({}) read "
                    + "from the input field's maxlength — retrying once with an explicit correction hint. "
                    + "First attempt: [{}]",
                solved.length(), expectedLength, solved);
            try {
                String retried = visionClient.resolve(captchaFile, expectedLength, solved);
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

        // Cross-check the model's own case judgment for case-symmetric letters
        // (C/c, O/o, S/s, U/u, V/v, W/w, X/x, Z/z) against actual pixel height —
        // see applyRelativeCaseCorrectionToVisionAnswer() javadoc. Runs after
        // the length-mismatch retry above so it always measures the final,
        // length-correct answer rather than a since-discarded first attempt.
        solved = segmenter.applyRelativeCaseCorrectionToVisionAnswer(captchaFile, solved);

        return solved;
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
        // Polls until the browser reports img.complete; the value is then the final
        // naturalWidth (> 0 loaded, 0 = browser is DONE and the image is genuinely broken).
        Long naturalWidth = null;
        try {
            naturalWidth = new WebDriverWait(driver, Duration.ofSeconds(captchaImageLoadTimeoutSeconds))
                .pollingEvery(Duration.ofMillis(100))
                .until(d -> {
                    try {
                        Object result = ((JavascriptExecutor) d).executeScript(
                            "var img = arguments[0];"
                                + "if (img.complete) { return img.naturalWidth; }"
                                + "return null;",
                            imgElement);
                        return result instanceof Number ? ((Number) result).longValue() : null;
                    } catch (StaleElementReferenceException staleEx) {
                        throw new IllegalStateException(staleEx);
                    } catch (Exception jsEx) {
                        log.debug("Could not poll CAPTCHA image load state ({}) - retrying", jsEx.getMessage());
                        return null;
                    }
                });
        } catch (TimeoutException timeout) {
            // fall through to the "never finished loading" error below
        } catch (IllegalStateException wrapped) {
            if (wrapped.getCause() instanceof StaleElementReferenceException) {
                throw new IOException("[CaptchaSolver] CAPTCHA image element went stale while waiting for it "
                    + "to load - the page likely re-rendered the CAPTCHA out from under this call. "
                    + "Re-locate the element and retry.", wrapped.getCause());
            }
            throw wrapped;
        }
        if (naturalWidth != null && naturalWidth > 0) {
            return;
        }
        // naturalWidth == 0 with .complete == true: browser is DONE trying, a real broken image.
        throw new IOException("[CaptchaSolver] CAPTCHA image never finished loading within "
            + captchaImageLoadTimeoutSeconds + "s (naturalWidth stayed "
            + (naturalWidth != null ? naturalWidth : "unresolved") + ") - screenshotting/solving it now "
            + "would just read a broken-image placeholder (icon + alt text), not an actual CAPTCHA. This "
            + "usually means the page's own JS renders the CAPTCHA image asynchronously, later than "
            + "document.readyState=='complete' - increase captcha.image.load.timeout if the CAPTCHA is just "
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
     * Remove noise chars from OCR output.
     *
     * IMPORTANT: this must NOT force-uppercase the result. The constructor
     * already whitelists Tesseract to upper+lower+digits+symbols via
     * textCaptchaCharset specifically so mixed-case CAPTCHAs (e.g. the
     * india.ai site, which renders a genuine mix of upper and lower case
     * letters) can be read correctly — forcing .toUpperCase(Locale.ROOT) here silently
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
     * Evaluates a simple two-operand expression like "3 + 5", "12 - 4", "6 * 2".
     *
     * Returns null (not a made-up "0") when the OCR text doesn't hold exactly
     * two operands and one operator — a lost digit ("5 +"), a lost operator
     * ("3 5"), or more than two operands ("3+5-2"). The old version had two
     * problems: {@code parts[1]} on "5 +" threw an uncaught
     * ArrayIndexOutOfBoundsException (split() drops trailing empty strings),
     * and every other parse failure returned "0", which the caller then typed
     * into the field as if it were a real answer.
     */
    private String evaluateMathExpression(String rawText) {
        if (rawText == null) {
            return null;
        }
        // Normalize OCR artifacts
        String expr = rawText
            .replaceAll("[^0-9+\\-*/]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        log.debug("Evaluating math expression: [{}]", expr);

        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+)\\s*([+\\-*/])\\s*(\\d+)$").matcher(expr);
        if (!m.matches()) {
            log.error("Math evaluation failed: [{}] is not '<number> <operator> <number>'", expr);
            return null;
        }
        try {
            int left = Integer.parseInt(m.group(1));
            int right = Integer.parseInt(m.group(3));
            switch (m.group(2)) {
                case "+": return String.valueOf(left + right);
                case "-": return String.valueOf(left - right);
                case "*": return String.valueOf(left * right);
                default:  return String.valueOf(left / right);
            }
        } catch (NumberFormatException | ArithmeticException e) {
            log.error("Math evaluation failed for [{}]: {}", expr, e.getMessage());
            return null;
        }
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

            // Same AI/OCR cross-check as the initial solve (resolveTextCaptchaAnswer()) —
            // kept consistent rather than duplicating an older AI-then-OCR-fallback-only
            // version of this logic here.
            String corrected = resolveTextCaptchaAnswer(captchaFile, discoveredLength, aiEnabled);

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
