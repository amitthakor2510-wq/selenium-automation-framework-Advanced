package com.automation.core.ai;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Shared text-only LLM client for the framework's AI features:
 * AI-assisted self-healing ({@code selfhealing.AiLocatorHealer}), AI
 * root-cause analysis on test failure ({@link AiExceptionAnalyzer}), and
 * the AI-augmented bug crawler ({@code crawler.SiteCrawler}).
 *
 * Deliberately separate from {@code CaptchaSolver}'s own AI Vision call:
 * that one sends an image (a CAPTCHA screenshot) and is CaptchaSolver-
 * specific; this one is text-only and shared by everything else in the
 * framework that wants an LLM's opinion on plain text/HTML/logs.
 *
 * Same dual-provider shape as CaptchaSolver though, on its own {@code ai.*}
 * config namespace (kept separate from {@code captcha.ai.*} so the vision
 * model used for CAPTCHAs and the text/coding model used here can be
 * configured independently, which matters — they're different jobs):
 *
 * <ul>
 *   <li>{@code ai.provider=ollama} (default) — calls a local/remote Ollama
 *       server's {@code /api/chat}. No API key required.</li>
 *   <li>{@code ai.provider=anthropic} — calls the Anthropic Messages API.
 *       Needs {@code ai.apiKey} or the {@code ANTHROPIC_API_KEY} env var.</li>
 * </ul>
 *
 * "Best free model" note: for {@code ai.provider=ollama}, a coding-tuned
 * model in the Qwen-Coder family is currently the strongest generally-
 * available free/local pick for the reasoning these features need
 * (locator-candidate selection, failure root-causing, bug summarization).
 * See docs/AI_FEATURES.md for current sizing guidance — nothing in this
 * class hardcodes a model; {@code ai.model} always comes from config.
 */
public final class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final ObjectMapper objectMapper = LlmHttp.MAPPER;

    private OllamaClient() {
    }

    /** True when enough config is present to even attempt a call. */
    public static boolean isConfigured() {
        String model = ConfigReader.get("ai.model", "");
        if (model.isBlank()) {
            return false;
        }
        if (isAnthropic()) {
            String key = resolveApiKey();
            return key != null && !key.isBlank();
        }
        return true;
    }

    private static boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(ConfigReader.get("ai.provider", "ollama").trim());
    }

    private static String resolveApiKey() {
        String configured = ConfigReader.get("ai.apiKey", "");
        if (!configured.isBlank()) {
            return configured;
        }
        return System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
    }

    /**
     * Sends a single-turn chat request (optional system prompt + one user
     * prompt) and returns the model's raw text reply. Callers that need
     * structured data should ask for JSON in the prompt itself and parse
     * the result with {@link #extractJsonObject(String)} — Ollama's
     * {@code /api/chat} and Anthropic's Messages API don't share a
     * JSON-mode contract simple enough to normalize here.
     *
     * @throws IOException on any network/HTTP/response-shape failure —
     *         callers decide what "AI unavailable" should fall back to.
     */
    public static String chat(String systemPrompt, String userPrompt) throws IOException {
        String model = ConfigReader.get("ai.model", "");
        if (model.isBlank()) {
            throw new ConfigException("[AI] ai.model is not configured — set it to a model your ai.provider "
                + "can serve (e.g. a Qwen-Coder tag for ai.provider=ollama). See docs/AI_FEATURES.md.");
        }
        boolean anthropic = isAnthropic();
        // getNonBlank: ai.endpoint currently ships non-blank in global.properties (pointed at
        // this project's own Ollama box), but a blank override would otherwise silently resolve
        // to "" instead of the provider default — see ConfigReader.getNonBlank's javadoc.
        String endpoint = ConfigReader.getNonBlank("ai.endpoint",
            anthropic ? "https://api.anthropic.com/v1/messages" : "http://localhost:11434/api/chat");
        int timeoutSeconds = ConfigReader.getInt("ai.timeout.seconds", 60);

        ObjectNode requestBody = objectMapper.createObjectNode();
        HttpRequest.Builder requestBuilder = LlmHttp.newJsonRequest(endpoint, timeoutSeconds);

        if (anthropic) {
            String apiKey = resolveApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new ConfigException("[AI] ai.provider=anthropic but no API key configured. "
                    + "Set ai.apiKey or the ANTHROPIC_API_KEY environment variable.");
            }
            requestBody.put("model", model);
            requestBody.put("max_tokens", 1024);
            requestBody.put("temperature", 0);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBody.put("system", systemPrompt);
            }
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(userMessage);
            requestBody.set("messages", messages);

            LlmHttp.addAnthropicHeaders(requestBuilder, apiKey);
        } else {
            requestBody.put("model", model);
            requestBody.put("stream", false);

            ArrayNode messages = LlmHttp.ollamaMessages(systemPrompt);
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);
            requestBody.set("messages", messages);

            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", 0);
            requestBody.set("options", options);

            LlmHttp.addBearerIfPresent(requestBuilder, ConfigReader.get("ai.apiKey", ""));
        }

        HttpRequest request = LlmHttp.post(requestBuilder, requestBody);

        log.info("🤖 AI call: provider={}, model={}, endpoint={}",
            anthropic ? "anthropic" : "ollama", model, endpoint);

        return LlmHttp.execute(request, anthropic, "AI", "");
    }

    /**
     * Best-effort extraction of the first {@code {...}} JSON object found
     * in a model's reply — local models frequently wrap valid JSON in
     * prose or a {@code ```json} fence even when explicitly told not to.
     * Returns null (never throws) if no valid JSON object could be found,
     * so callers can fall back to their non-AI behavior instead of failing
     * outright.
     */
    public static JsonNode extractJsonObject(String rawReply) {
        if (rawReply == null) {
            return null;
        }
        int start = rawReply.indexOf('{');
        int end = rawReply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(rawReply.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
