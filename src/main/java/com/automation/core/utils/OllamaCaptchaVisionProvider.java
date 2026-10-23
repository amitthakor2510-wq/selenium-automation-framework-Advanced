package com.automation.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * A local/remote Ollama server's /api/generate endpoint (e.g. a self-hosted
 * llava model). Flat request, images in a bare base64 array, answer in a
 * top-level "response" string; needs no API key.
 */
final class OllamaCaptchaVisionProvider implements CaptchaVisionProvider {

    @Override
    public String defaultEndpoint() {
        return "http://localhost:11434/api/generate";
    }

    /** Ollama serves models locally/on a LAN box with no auth by default. */
    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public ObjectNode buildRequestBody(ObjectMapper mapper, String model, String prompt,
                                       String base64Image, String mediaType) {
        // image(s) as a bare base64 array (no data-URI prefix, no per-image media type),
        // "stream": false so the whole answer comes back as one JSON object instead of
        // newline-delimited partial-token chunks.
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        ArrayNode images = mapper.createArrayNode();
        images.add(base64Image);
        requestBody.set("images", images);
        requestBody.put("stream", false);
        ObjectNode options = mapper.createObjectNode();
        options.put("temperature", 0);
        requestBody.set("options", options);
        return requestBody;
    }

    @Override
    public void addAuthHeaders(HttpRequest.Builder builder, String apiKey) {
        // Most local setups need no auth at all; if a key IS configured anyway (e.g. an
        // nginx reverse proxy in front of a remote box adding its own auth), send it as a
        // bearer token rather than silently dropping it.
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    @Override
    public String extractAnswer(JsonNode root, String rawBody) throws IOException {
        // {"model":"llava", "response":"ABC123", "done":true, ...}
        JsonNode responseNode = root.get("response");
        if (responseNode == null || responseNode.asText().isBlank()) {
            JsonNode errorNode = root.get("error");
            String detail = errorNode != null ? errorNode.asText() : rawBody;
            throw new IOException("Ollama Vision response had no usable \"response\" field: " + detail);
        }
        return responseNode.asText();
    }
}
