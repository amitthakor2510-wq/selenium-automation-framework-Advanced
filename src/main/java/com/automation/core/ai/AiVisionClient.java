package com.automation.core.ai;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Shared image+text LLM client for AI features that need to look at a
 * screenshot rather than raw HTML/XML text ({@link OllamaClient}'s job) —
 * currently used by the visual bug-review pass in both the web bug crawler
 * ({@code crawler.ai.vision.enabled}) and the mobile app bug crawler
 * ({@code crawler.mobile.ai.vision.enabled}); see
 * {@code com.automation.core.crawler.AiScreenshotReviewer}.
 *
 * Deliberately separate from {@code CaptchaSolver}'s own AI Vision call
 * ({@code captcha.ai.*}): that one is CAPTCHA-specific (character-length
 * hints, confusable-character guidance) and stays with the rest of
 * CaptchaSolver's CAPTCHA-only logic. This is a general "here's a
 * screenshot, what looks visually wrong" call, on its own {@code
 * ai.vision.*} config namespace so it can be pointed at a different
 * model/provider than either OllamaClient's text model or CaptchaSolver's
 * CAPTCHA-solving model.
 *
 * Same dual-provider shape as OllamaClient/CaptchaSolver:
 * <ul>
 *   <li>{@code ai.vision.provider=anthropic} (default) — Anthropic Messages
 *       API, needs {@code ai.vision.apiKey} or the {@code ANTHROPIC_API_KEY}
 *       env var, and a vision-capable {@code ai.vision.model}.</li>
 *   <li>{@code ai.vision.provider=ollama} — a local vision-capable model
 *       (e.g. llava, bakllava, moondream) served by Ollama's
 *       {@code /api/chat} with an {@code images} field. No API key
 *       required.</li>
 * </ul>
 */
public final class AiVisionClient {

    private static final Logger log = LoggerFactory.getLogger(AiVisionClient.class);
    private static final ObjectMapper objectMapper = LlmHttp.MAPPER;

    private AiVisionClient() {
    }

    /** True when enough config is present to even attempt a call. */
    public static boolean isConfigured() {
        String model = ConfigReader.get("ai.vision.model", "");
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
        return "anthropic".equalsIgnoreCase(ConfigReader.get("ai.vision.provider", "anthropic").trim());
    }

    private static String resolveApiKey() {
        String configured = ConfigReader.get("ai.vision.apiKey", "");
        if (!configured.isBlank()) {
            return configured;
        }
        return System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
    }

    /**
     * Sends one base64-encoded PNG screenshot plus a text prompt and
     * returns the model's raw text reply. Same "ask for JSON in the
     * prompt, parse with {@link OllamaClient#extractJsonObject(String)}"
     * contract as {@link OllamaClient#chat(String, String)}.
     *
     * @throws IOException on any network/HTTP/response-shape failure —
     *         callers decide what "vision AI unavailable" should fall
     *         back to.
     */
    public static String review(String systemPrompt, String userPrompt, String base64Png) throws IOException {
        String model = ConfigReader.get("ai.vision.model", "");
        if (model.isBlank()) {
            throw new ConfigException("[AiVisionClient] ai.vision.model is not configured — set it to a "
                + "vision-capable model (a Claude model for ai.vision.provider=anthropic, or a local llava/"
                + "moondream tag for ai.vision.provider=ollama). See docs/AI_FEATURES.md.");
        }
        boolean anthropic = isAnthropic();
        // getNonBlank, not get: global.properties ships ai.vision.endpoint blank on purpose
        // ("use the provider's default") — plain get(key, default) only substitutes the
        // default when the key is ABSENT, not when it's present-but-empty, so this used to
        // silently resolve to "" and break every call. See ConfigReader.getNonBlank's javadoc.
        String endpoint = ConfigReader.getNonBlank("ai.vision.endpoint",
            anthropic ? "https://api.anthropic.com/v1/messages" : "http://localhost:11434/api/chat");
        int timeoutSeconds = ConfigReader.getInt("ai.vision.timeout.seconds", 60);

        ObjectNode requestBody = objectMapper.createObjectNode();
        HttpRequest.Builder requestBuilder = LlmHttp.newJsonRequest(endpoint, timeoutSeconds);

        if (anthropic) {
            String apiKey = resolveApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new ConfigException("[AiVisionClient] ai.vision.provider=anthropic but no API key "
                    + "configured. Set ai.vision.apiKey or the ANTHROPIC_API_KEY environment variable.");
            }
            requestBody.put("model", model);
            requestBody.put("max_tokens", 1024);
            requestBody.put("temperature", 0);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBody.put("system", systemPrompt);
            }

            ObjectNode imageBlock = objectMapper.createObjectNode();
            imageBlock.put("type", "image");
            ObjectNode source = objectMapper.createObjectNode();
            source.put("type", "base64");
            source.put("media_type", "image/png");
            source.put("data", base64Png);
            imageBlock.set("source", source);

            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", userPrompt);

            ArrayNode content = objectMapper.createArrayNode();
            content.add(imageBlock);
            content.add(textBlock);

            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.set("content", content);

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
            ArrayNode images = objectMapper.createArrayNode();
            images.add(base64Png);
            userMessage.set("images", images);
            messages.add(userMessage);
            requestBody.set("messages", messages);

            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", 0);
            requestBody.set("options", options);

            LlmHttp.addBearerIfPresent(requestBuilder, ConfigReader.get("ai.vision.apiKey", ""));
        }

        HttpRequest request = LlmHttp.post(requestBuilder, requestBody);

        log.info("🤖 AI vision call: provider={}, model={}, endpoint={}",
            anthropic ? "anthropic" : "ollama", model, endpoint);

        return LlmHttp.execute(request, anthropic, "AI vision", "vision ");
    }
}
