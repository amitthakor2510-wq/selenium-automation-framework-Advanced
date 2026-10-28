package com.automation.core.ai;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.CredentialTransportGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP plumbing shared by {@link OllamaClient} (text) and
 * {@link AiVisionClient} (screenshots): the shared {@link HttpClient} and
 * {@link ObjectMapper}, request building, the send / status check, and
 * reading the model's reply out of an Anthropic or Ollama {@code /api/chat}
 * response. Each client keeps only what really differs - its config
 * namespace and the shape of its request body.
 */
final class LlmHttp {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private LlmHttp() {
    }

    /** A POST-ready JSON request builder for {@code endpoint}. */
    static HttpRequest.Builder newJsonRequest(String endpoint, int timeoutSeconds) {
        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json");
    }

    static void addAnthropicHeaders(HttpRequest.Builder builder, String apiKey) {
        builder.header("x-api-key", apiKey);
        builder.header("anthropic-version", "2023-06-01");
    }

    /**
     * Most local Ollama setups need no auth at all; only sent if explicitly
     * configured (e.g. a reverse proxy adding its own).
     */
    static void addBearerIfPresent(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    /** Ollama {@code messages} array, pre-seeded with the system message when there is one. */
    static ArrayNode ollamaMessages(String systemPrompt) {
        ArrayNode messages = MAPPER.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = MAPPER.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        return messages;
    }

    static HttpRequest post(HttpRequest.Builder builder, ObjectNode body) throws IOException {
        HttpRequest request = builder
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
        boolean carriesCredential = request.headers().firstValue("x-api-key").isPresent()
            || request.headers().firstValue("Authorization").isPresent();
        CredentialTransportGuard.requireSecure(request.uri(), carriesCredential,
            ConfigReader.getBoolean("ai.allowInsecureTransport", false), "LLM call");
        return request;
    }

    /**
     * Sends the request and returns the model's raw text reply.
     *
     * @param label error-message prefix, e.g. {@code "AI"} or {@code "AI vision"}
     * @param kind  extra word in the Ollama error text, {@code ""} or {@code "vision "}
     * @throws IOException on any network/HTTP/response-shape failure
     */
    static String execute(HttpRequest request, boolean anthropic, String label, String kind) throws IOException {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(label + " call interrupted: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new IOException(label + " call returned HTTP " + response.statusCode() + ": "
                + CredentialTransportGuard.abbreviate(response.body()));
        }

        JsonNode root = MAPPER.readTree(response.body());
        if (anthropic) {
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                throw new IOException(label + " response had no content block: "
                    + CredentialTransportGuard.abbreviate(response.body()));
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentArray) {
                JsonNode textNode = block.get("text");
                if (textNode != null) {
                    sb.append(textNode.asText());
                }
            }
            return sb.toString();
        }
        // {"model":"...", "message":{"role":"assistant","content":"..."}, "done":true, ...}
        JsonNode messageNode = root.get("message");
        JsonNode contentNode = messageNode == null ? null : messageNode.get("content");
        if (contentNode == null) {
            JsonNode errorNode = root.get("error");
            String detail = errorNode != null ? errorNode.asText() : CredentialTransportGuard.abbreviate(response.body());
            throw new IOException("Ollama /api/chat " + kind + "response had no usable message.content field: "
                + detail);
        }
        return contentNode.asText();
    }
}
