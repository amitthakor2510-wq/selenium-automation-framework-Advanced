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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

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
        String endpoint = ConfigReader.get("ai.endpoint",
            anthropic ? "https://api.anthropic.com/v1/messages" : "http://localhost:11434/api/chat");
        int timeoutSeconds = ConfigReader.getInt("ai.timeout.seconds", 60);

        ObjectNode requestBody = objectMapper.createObjectNode();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json");

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

            requestBuilder.header("x-api-key", apiKey);
            requestBuilder.header("anthropic-version", "2023-06-01");
        } else {
            requestBody.put("model", model);
            requestBody.put("stream", false);

            ArrayNode messages = objectMapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode systemMessage = objectMapper.createObjectNode();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
                messages.add(systemMessage);
            }
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);
            requestBody.set("messages", messages);

            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", 0);
            requestBody.set("options", options);

            // Most local Ollama setups need no auth at all; only sent if
            // explicitly configured (e.g. a reverse proxy adding its own).
            String apiKey = ConfigReader.get("ai.apiKey", "");
            if (!apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
        }

        HttpRequest request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

        log.info("🤖 AI call: provider={}, model={}, endpoint={}",
            anthropic ? "anthropic" : "ollama", model, endpoint);

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("AI call interrupted: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("AI call returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (anthropic) {
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                throw new IOException("AI response had no content block: " + response.body());
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentArray) {
                JsonNode textNode = block.get("text");
                if (textNode != null) {
                    sb.append(textNode.asText());
                }
            }
            return sb.toString();
        } else {
            // {"model":"...", "message":{"role":"assistant","content":"..."}, "done":true, ...}
            JsonNode messageNode = root.get("message");
            JsonNode contentNode = messageNode == null ? null : messageNode.get("content");
            if (contentNode == null) {
                JsonNode errorNode = root.get("error");
                String detail = errorNode != null ? errorNode.asText() : response.body();
                throw new IOException("Ollama /api/chat response had no usable message.content field: " + detail);
            }
            return contentNode.asText();
        }
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
