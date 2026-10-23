package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * AI Vision stage of the CAPTCHA pipeline, split out of {@link CaptchaSolver}.
 * Owns the {@code captcha.ai.*} provider config (provider, apiKey, model,
 * endpoint), the vision prompt, the HTTP call with its retry policy and the
 * parsing of the model's reply. Everything provider-specific (request
 * shape, auth headers, reply shape) lives behind {@link CaptchaVisionProvider}
 * - see {@link AnthropicCaptchaVisionProvider} and
 * {@link OllamaCaptchaVisionProvider}.
 *
 * Behaviour is unchanged from when this code lived in CaptchaSolver.
 */
final class CaptchaVisionClient {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVisionClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // captcha.ai.provider: "anthropic" (default) or "ollama" - a local/remote
    // Ollama server running a vision-capable model (e.g. llava). The two have
    // completely different request/response JSON shapes, hence the provider
    // strategy instead of just a different endpoint URL.
    private final String aiProvider = ConfigReader.get("captcha.ai.provider", "anthropic").trim().toLowerCase(Locale.ROOT);
    private final CaptchaVisionProvider provider = CaptchaVisionProvider.forName(aiProvider);
    private final String aiApiKey = ConfigReader.get("captcha.ai.apiKey",
        System.getenv().getOrDefault("ANTHROPIC_API_KEY", ""));
    private final String aiModel = ConfigReader.get("captcha.ai.model", "");
    // getNonBlank: a blank override (valid "use the default" choice) would otherwise
    // silently resolve to "" instead of the provider default - see
    // ConfigReader.getNonBlank's javadoc.
    private final String aiEndpoint = ConfigReader.getNonBlank("captcha.ai.endpoint", provider.defaultEndpoint());

    private final String textCaptchaCharset;
    private final UnaryOperator<String> answerCleaner;

    /**
     * @param textCaptchaCharset the CAPTCHA's allowed character set (folded into the prompt)
     * @param answerCleaner      strips characters outside that set from the model's raw reply
     */
    CaptchaVisionClient(String textCaptchaCharset, UnaryOperator<String> answerCleaner) {
        this.textCaptchaCharset = textCaptchaCharset;
        this.answerCleaner = answerCleaner;
    }

    /**
     * Sends a CAPTCHA screenshot to a vision-capable LLM and returns exactly
     * the characters it reports, or throws if the call can't be completed.
     * Callers are expected to catch and fall back to OCR — this method
     * itself makes no OCR attempt. Use CaptchaSolver.solveViaVision() rather than calling
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
    String resolve(File captchaFile, int expectedLength, String previousAttempt) throws Exception {
        // Ollama serves models locally/on a LAN box with no auth by default,
        // so it's the one provider that legitimately has no API key at all —
        // don't hard-fail on a blank aiApiKey for it.
        if (provider.requiresApiKey() && (aiApiKey == null || aiApiKey.isBlank())) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but no API key configured. "
                + "Set captcha.ai.apiKey (or the ANTHROPIC_API_KEY environment variable).");
        }
        if (aiModel == null || aiModel.isBlank()) {
            throw new ConfigException("[CaptchaSolver] AI Vision solve requested but captcha.ai.model is not "
                + "set. Configure it to a vision-capable model you have access to (e.g. a current Claude "
                + "or GPT-4o-class model, or a local Ollama model like llava) via captcha.ai.model.");
        }

        String base64Image = encodeImageToBase64(captchaFile);
        String mediaType = captchaFile.getName().toLowerCase(Locale.ROOT).endsWith(".jpg")
            || captchaFile.getName().toLowerCase(Locale.ROOT).endsWith(".jpeg") ? "image/jpeg" : "image/png";
        String promptText = buildVisionPrompt(expectedLength, previousAttempt);

        // Visible at the default INFO log level (not DEBUG) — this is the
        // line to grep for to confirm which provider/model/endpoint a given
        // solve actually went to, e.g. to tell "it silently fell back to
        // OCR" apart from "it called Ollama and got a wrong answer".
        log.info("🤖 Vision API call: provider={}, model={}, endpoint={}, image={}{}",
            aiProvider, aiModel, aiEndpoint, captchaFile.getAbsolutePath(),
            previousAttempt != null ? " (retry, previous=[" + previousAttempt + "])" : "");

        ObjectNode requestBody = provider.buildRequestBody(objectMapper, aiModel, promptText, base64Image, mediaType);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(aiEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
        provider.addAuthHeaders(requestBuilder, aiApiKey);
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
        StringBuilder rawAnswer = new StringBuilder(provider.extractAnswer(root, response.body()));

        // Log the model's raw, uncleaned reply at INFO — this is what the
        // model literally said before the solver's answer cleaner strips stray
        // whitespace/punctuation, useful for telling "the model misread the
        // image" apart from "the model read it right but cleaning mangled
        // it" when an answer comes back wrong.
        log.info("🤖 Vision API raw reply ({}): [{}]", aiProvider, rawAnswer);

        // Still route through the solver's answer cleaner's charset filter — strips any
        // stray whitespace/punctuation the model might add despite the
        // instruction, without forcing case (see that method's own javadoc
        // on why case must never be forced here).
        String cleaned = answerCleaner.apply(rawAnswer.toString());
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
    /**
     * NOTE: the confusable-pairs list below is hand-written prose (each pair
     * needs its own "how to tell them apart" clue, which a generic loop over
     * CONFUSABLE_PAIRS can't produce) and is NOT generated from
     * CONFUSABLE_PAIRS above — it must be kept in sync with it manually.
     * This already drifted out of sync once (CONFUSABLE_PAIRS has had '2'/'Z'
     * for a while; this prompt never mentioned it until a real SAHMAT
     * CAPTCHA got misread as a direct result). When adding a new pair to
     * CONFUSABLE_PAIRS for the OCR side, add the equivalent guidance here too.
     */
    private String buildVisionPrompt(int expectedLength, String previousAttempt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("This image is a distorted-text CAPTCHA. Reply with ONLY the exact characters "
            + "shown in the image, preserving upper/lower case exactly as rendered. No explanation, "
            + "no punctuation, no markdown, no leading/trailing whitespace — just the characters "
            + "themselves, nothing else.\n\n");

        prompt.append("Every character in this CAPTCHA is one of: ").append(textCaptchaCharset).append(". ")
            .append("Do not output any character outside that set.\n\n");

        prompt.append("The image may contain a thin, faint gray or black decorative line running across "
            + "it (a strike-through or wavy background line). This line is NOT a character and carries no "
            + "meaning — ignore it completely when identifying characters, even where it crosses through "
            + "or overlaps one. Only the bold, solid-colored strokes are the actual characters; do not let "
            + "the thin line close up, extend, or change the shape of a letter in your reading.\n\n");

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
            + "'m' vs 'n' vs 'h', 'c' vs 'e'.\n"
            + "- 'f' vs 'a' — 'f' has a tall ascender that rises clearly above the other lowercase "
            + "characters, plus a horizontal crossbar partway up, and its bottom is an open curve or "
            + "hook, never a fully closed loop. 'a' has no ascender at all (same height as other "
            + "lowercase letters) and its bottom is a fully closed, round bowl shape. A stray line "
            + "crossing an 'f' does not make it a closed bowl — judge by the ascender's presence alone.\n"
            + "- '2' vs 'Z'/'z' — a '2' has a curved top (like the top of an 'S') and a flat, straight "
            + "bottom stroke; a 'Z'/'z' has two straight, flat horizontal strokes joined by a single "
            + "diagonal, with no curve anywhere. Only report 'Z'/'z' if you see two flat horizontals "
            + "and no curve at the top.\n"
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

    /** Encode image file to Base64 string (needed for AI Vision APIs). */
    private String encodeImageToBase64(File imageFile) throws IOException {
        byte[] bytes = FileUtils.readFileToByteArray(imageFile);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
