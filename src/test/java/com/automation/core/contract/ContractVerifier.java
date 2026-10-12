package com.automation.core.contract;

import com.automation.core.contract.Contract.Interaction;
import com.automation.core.utils.SensitiveData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replays one contract interaction against a provider and reports every way the real response
 * differs from what the consumer relies on. The comparison ({@link #compare}) is pure; sending
 * the request is delegated to an {@link Exchange}, so the same logic runs through RestAssured
 * in a real run ({@link RestAssuredExchange}) and through a fake in unit tests.
 */
public final class ContractVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sends a request to the provider. */
    @FunctionalInterface
    public interface Exchange {
        ActualResponse send(Contract.Request request) throws Exception;
    }

    /** What the provider actually answered. Header names are matched case-insensitively. */
    public record ActualResponse(int status, Map<String, String> headers, String body) {
    }

    /** Outcome of one interaction: no mismatches means the provider still honours it. */
    public record Result(Interaction interaction, List<String> mismatches, ActualResponse response) {
        public boolean passed() {
            return mismatches.isEmpty();
        }
    }

    private ContractVerifier() {
    }

    /** Sets up the provider state(s), sends the request and compares the answer. Never throws for a broken provider - that is a failed {@link Result}. */
    public static Result verify(Interaction interaction, Exchange exchange, ProviderStateHandler states) {
        List<String> mismatches = new ArrayList<>();
        List<String> unsafe = Contract.requestProblems(interaction.request());
        if (!unsafe.isEmpty()) {
            // Nothing is sent and no provider state is set up: the file is rejected before it can do anything.
            for (String problem : unsafe) {
                mismatches.add("unsafe contract request, not sent: " + problem);
            }
            return new Result(interaction, mismatches, null);
        }
        try {
            if (states != null) {
                for (String state : interaction.providerStates()) {
                    states.setUp(state);
                }
            }
            ActualResponse actual = exchange.send(interaction.request());
            mismatches.addAll(compare(interaction.response(), actual));
            return new Result(interaction, mismatches, actual);
        } catch (Exception e) {
            mismatches.add("could not complete the request: " + e);
            return new Result(interaction, mismatches, null);
        }
    }

    /** Status, headers and body of the actual response against the contract's expectations. */
    public static List<String> compare(Contract.Response expected, ActualResponse actual) {
        List<String> mismatches = new ArrayList<>();
        if (actual.status() != expected.status()) {
            mismatches.add("status: expected " + expected.status() + " but was " + actual.status());
        }
        for (Map.Entry<String, String> header : expected.headers().entrySet()) {
            String actualValue = headerValue(actual.headers(), header.getKey());
            if (actualValue == null) {
                mismatches.add("header '" + header.getKey() + "': missing from the response");
            } else if (!headerEquals(header.getKey(), header.getValue(), actualValue)) {
                boolean secret = isSecretHeader(header.getKey());
                mismatches.add("header '" + header.getKey() + "': expected '" + (secret ? SensitiveData.MASK : header.getValue())
                    + "' but was '" + (secret ? SensitiveData.MASK : actualValue) + "'");
            }
        }
        JsonNode expectedBody = expected.body();
        if (expectedBody != null && !expectedBody.isNull()) {
            JsonNode actualBody = parse(actual.body());
            if (actualBody == null) {
                mismatches.add("body: the response has no body");
            } else {
                mismatches.addAll(BodyMatcher.match(expectedBody, actualBody, expected.bodyRules()));
            }
        }
        return mismatches;
    }

    private static boolean isSecretHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SensitiveData.isSensitiveName(name) || lower.equals("authorization") || lower.equals("proxy-authorization")
            || lower.equals("cookie") || lower.equals("set-cookie");
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception notJson) {
            return TextNode.valueOf(body.strip());
        }
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /** Content-Type compares the media type only: "application/json; charset=utf-8" satisfies "application/json". */
    private static boolean headerEquals(String name, String expected, String actual) {
        if (name.equalsIgnoreCase("content-type")) {
            return mediaType(expected).equals(mediaType(actual));
        }
        return expected.trim().equals(actual.trim());
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
