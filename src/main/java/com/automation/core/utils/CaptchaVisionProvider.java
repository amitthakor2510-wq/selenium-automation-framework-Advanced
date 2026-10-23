package com.automation.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * The provider-specific part of a CAPTCHA vision call: how the request body
 * is shaped, how it authenticates and where the answer sits in the reply.
 * {@link CaptchaVisionClient} does everything else (prompt, HTTP, retries).
 */
interface CaptchaVisionProvider {

    /** Endpoint used when {@code captcha.ai.endpoint} is blank. */
    String defaultEndpoint();

    /** Whether a blank API key is a configuration error for this provider. */
    boolean requiresApiKey();

    ObjectNode buildRequestBody(ObjectMapper mapper, String model, String prompt,
                                String base64Image, String mediaType);

    void addAuthHeaders(HttpRequest.Builder builder, String apiKey);

    /** Pulls the model's raw text out of a 200 reply; throws if there is none. */
    String extractAnswer(JsonNode root, String rawBody) throws IOException;

    /** "ollama" selects Ollama; anything else keeps the historical Anthropic default. */
    static CaptchaVisionProvider forName(String name) {
        return "ollama".equals(name) ? new OllamaCaptchaVisionProvider() : new AnthropicCaptchaVisionProvider();
    }
}
