package com.automation.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpRequest;

/** Anthropic Messages API (api.anthropic.com/v1/messages) with an image content block. */
final class AnthropicCaptchaVisionProvider implements CaptchaVisionProvider {

    @Override
    public String defaultEndpoint() {
        return "https://api.anthropic.com/v1/messages";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public ObjectNode buildRequestBody(ObjectMapper mapper, String model, String prompt,
                                       String base64Image, String mediaType) {
        ObjectNode imageSource = mapper.createObjectNode();
        imageSource.put("type", "base64");
        imageSource.put("media_type", mediaType);
        imageSource.put("data", base64Image);

        ObjectNode imageBlock = mapper.createObjectNode();
        imageBlock.put("type", "image");
        imageBlock.set("source", imageSource);

        ObjectNode textBlock = mapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);

        ArrayNode content = mapper.createArrayNode();
        content.add(imageBlock);
        content.add(textBlock);

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message);

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", 32);
        requestBody.put("temperature", 0);
        requestBody.set("messages", messages);
        return requestBody;
    }

    @Override
    public void addAuthHeaders(HttpRequest.Builder builder, String apiKey) {
        builder.header("x-api-key", apiKey);
        builder.header("anthropic-version", "2023-06-01");
    }

    @Override
    public String extractAnswer(JsonNode root, String rawBody) throws IOException {
        JsonNode contentArray = root.get("content");
        if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
            throw new IOException("Vision API response had no content block: " + rawBody);
        }
        StringBuilder answer = new StringBuilder();
        for (JsonNode block : contentArray) {
            JsonNode textNode = block.get("text");
            if (textNode != null) {
                answer.append(textNode.asText());
            }
        }
        return answer.toString();
    }
}
