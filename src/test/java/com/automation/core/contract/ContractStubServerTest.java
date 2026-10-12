package com.automation.core.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP against the stub, and the full consumer-stub / provider-verify round trip. */
class ContractStubServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Contract contract() throws IOException {
        return Contract.parse(new ObjectMapper().readTree(ContractTest.V2), "t.json");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An Exchange that really calls a server over HTTP, like RestAssuredExchange does in a test run. */
    private static ContractVerifier.Exchange over(String baseUrl) {
        return request -> {
            StringBuilder url = new StringBuilder(baseUrl + request.path());
            String sep = "?";
            for (Map.Entry<String, java.util.List<String>> param : request.query().entrySet()) {
                for (String value : param.getValue()) {
                    url.append(sep).append(java.net.URLEncoder.encode(param.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                        .append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                    sep = "&";
                }
            }
            HttpResponse<String> response = get(url.toString());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
            return new ContractVerifier.ActualResponse(response.statusCode(), headers, response.body());
        };
    }

    @Test
    void servesTheContractedResponseAndTracksUsage() throws Exception {
        try (ContractStubServer stub = ContractStubServer.start(contract())) {
            assertEquals(List.of("list").toString(), stub.unusedInteractions().toString());
            HttpResponse<String> response = get(stub.baseUrl() + "/books?a=1&b=x%20y&a=2");
            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
            assertEquals("[{\"id\":1}]", response.body());
            assertTrue(stub.unusedInteractions().isEmpty());
            stub.assertAllInteractionsUsed();
        }
    }

    @Test
    void aRequestOutsideTheContractGetsA500AndIsReported() throws Exception {
        try (ContractStubServer stub = ContractStubServer.start(contract())) {
            assertEquals(500, get(stub.baseUrl() + "/unknown").statusCode());
            assertEquals(500, get(stub.baseUrl() + "/books?a=9").statusCode(), "query parameters must match too");
            assertEquals(2, stub.unmatchedRequests().size());
            try {
                stub.assertAllInteractionsUsed();
                throw new IllegalStateException("should have failed");
            } catch (AssertionError e) {
                assertTrue(e.getMessage().contains("requests not in the contract"), e.getMessage());
                assertTrue(e.getMessage().contains("never used"), e.getMessage());
            }
        }
    }

    @Test
    void theStubSatisfiesItsOwnContractAndAChangedProviderDoesNot() throws Exception {
        Contract.Interaction interaction = contract().interactions().get(0);
        try (ContractStubServer stub = ContractStubServer.start(contract())) {
            assertTrue(ContractVerifier.verify(interaction, over(stub.baseUrl()), null).passed(),
                "consumer stub and provider verification agree on the same file");
        }
        String changed = ContractTest.V2.replace("[{\"id\":1}]", "[{\"id\":\"one\"}]")
            .replace("\"$.body[*].id\":{\"match\":\"integer\"}", "\"$.body[*].id\":{\"match\":\"integer\"}");
        // the "provider" now serves ids as strings, the consumer's contract still demands integers
        Contract providerSide = Contract.parse(new ObjectMapper().readTree(changed), "provider.json");
        try (ContractStubServer changedProvider = ContractStubServer.start(providerSide)) {
            ContractVerifier.Result result = ContractVerifier.verify(interaction, over(changedProvider.baseUrl()), null);
            assertFalse(result.passed());
            assertTrue(result.mismatches().contains("$[0].id: expected an integer but was \"one\""), result.mismatches().toString());
        }
    }
}
