package com.automation.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hostile providers and hostile contract files: nothing here may hang, crash, or reach the network. */
class ContractSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Contract.Interaction interaction(String requestJson, String responseJson) throws IOException {
        String json = "{\"interactions\":[{\"description\":\"d\",\"request\":" + requestJson + ",\"response\":" + responseJson + "}]}";
        return Contract.parse(MAPPER.readTree(json), "t.json").interactions().get(0);
    }

    // ── ReDoS: the provider's RESPONSE is the attacker-controlled input to a contract regex ──

    @Test
    void aCatastrophicRegexAgainstAHostileResponseFailsFastInsteadOfHangingTheRun() throws Exception {
        Contract.Interaction bomb = interaction("{\"path\":\"/x\"}",
            "{\"status\":200,\"body\":{\"v\":\"a\"},\"matchingRules\":{\"$.body.v\":{\"match\":\"regex\",\"regex\":\"(.*a){25}\"}}}");
        String hostile = "{\"v\":\"" + "a".repeat(40) + "!\"}";
        List<String> mismatches = new ArrayList<>();
        long startedNanos = System.nanoTime();
        Thread worker = new Thread(() -> mismatches.addAll(ContractVerifier.compare(bomb.response(),
            new ContractVerifier.ActualResponse(200, Map.of(), hostile))));
        worker.setDaemon(true);
        worker.start();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "regex evaluation did not finish in 10 s - catastrophic backtracking");
        assertEquals(1, mismatches.size(), mismatches.toString());
        assertTrue(mismatches.get(0).contains("took too long"), mismatches.get(0));
        assertTrue(System.nanoTime() - startedNanos < 5_000_000_000L, "it must give up quickly, not just eventually");
    }

    @Test
    void anInvalidOrMissingRegexIsAMismatchNotACrash() throws Exception {
        Contract.Interaction broken = interaction("{\"path\":\"/x\"}",
            "{\"status\":200,\"body\":{\"a\":\"1\",\"b\":\"2\"},\"matchingRules\":{"
                + "\"$.body.a\":{\"match\":\"regex\",\"regex\":\"([\"},\"$.body.b\":{\"match\":\"regex\"}}}");
        List<String> mismatches = ContractVerifier.compare(broken.response(),
            new ContractVerifier.ActualResponse(200, Map.of(), "{\"a\":\"1\",\"b\":\"2\"}"));
        assertEquals(2, mismatches.size(), mismatches.toString());
        assertTrue(mismatches.get(0).contains("invalid regex"), mismatches.toString());
        assertTrue(mismatches.get(1).contains("no regex"), mismatches.toString());
    }

    // ── SSRF / header injection through a (possibly PR-supplied) contract file ──

    @Test
    void aContractCannotMakeTheVerifierCallAnotherHostOrInjectHeaders() throws Exception {
        String[] badRequests = {
            "{\"path\":\"http://169.254.169.254/latest/meta-data\"}",
            "{\"path\":\"https://evil.example/steal\"}",
            "{\"path\":\"//evil.example/steal\"}",
            "{\"path\":\"relative/no-slash\"}",
            "{\"path\":\"/ok\\r\\nX-Injected: 1\"}",
            "{\"path\":\"/ok\",\"headers\":{\"X-Evil\":\"a\\r\\nSet-Cookie: b=c\"}}",
            "{\"path\":\"/ok\",\"headers\":{\"X-Evil\\n\":\"a\"}}",
            "{\"path\":\"/ok\",\"method\":\"GET /admin HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n\"}"
        };
        for (String request : badRequests) {
            AtomicBoolean sent = new AtomicBoolean();
            ContractVerifier.Result result = ContractVerifier.verify(interaction(request, "{\"status\":200}"),
                r -> {
                    sent.set(true);
                    return new ContractVerifier.ActualResponse(200, Map.of(), "");
                }, null);
            assertFalse(sent.get(), "the request must never be sent: " + request);
            assertFalse(result.passed(), request);
            assertTrue(result.mismatches().get(0).startsWith("unsafe contract request"), result.mismatches().toString());
        }
    }

    @Test
    void aNormalRequestIsStillSent() throws Exception {
        AtomicBoolean sent = new AtomicBoolean();
        ContractVerifier.verify(interaction("{\"path\":\"/posts/1\",\"query\":\"a=1\",\"headers\":{\"Accept\":\"application/json\"}}",
            "{\"status\":200}"), r -> {
                sent.set(true);
                return new ContractVerifier.ActualResponse(200, Map.of(), "");
            }, null);
        assertTrue(sent.get());
    }

    // ── Malformed contract files must not pass silently ──

    @Test
    void emptyOrNonContractFilesAreReportedAsProblems() throws IOException {
        assertFalse(Contract.parse(MAPPER.readTree("{}"), "empty.json").validate().isEmpty());
        assertFalse(Contract.parse(MAPPER.readTree("[]"), "array.json").validate().isEmpty());
        assertFalse(Contract.parse(MAPPER.readTree("{\"interactions\":[]}"), "none.json").validate().isEmpty());
        assertTrue(Contract.parse(MAPPER.readTree(ContractTest.V2), "ok.json").validate().isEmpty());
        assertTrue(Contract.parse(MAPPER.readTree(ContractTest.V3), "ok3.json").validate().isEmpty());
    }

    // ── The stub server answers hostile requests safely ──

    @Test
    void theStubsErrorBodyIsAlwaysValidJsonWhateverThePathSays() throws Exception {
        try (ContractStubServer stub = ContractStubServer.start(Contract.parse(MAPPER.readTree(ContractTest.V2), "t.json"))) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create(stub.baseUrl() + "/%22%7D%5C%22quote%22%0A%3Cscript%3E")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            JsonNode error = MAPPER.readTree(response.body());
            assertTrue(error.get("error").asText().contains("\"}\\\"quote\""), error.toString());
        }
    }

    @Test
    void anOversizedRequestBodyDoesNotBreakTheStub() throws Exception {
        try (ContractStubServer stub = ContractStubServer.start(Contract.parse(MAPPER.readTree(ContractTest.V2), "t.json"))) {
            HttpClient client = HttpClient.newHttpClient();
            try {
                client.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/books"))
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(3_000_000))).build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException ignoredIfTheServerHangsUpOnTheFlood) {
                // acceptable: the point is that the server survives it
            }
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/books?a=1&b=x%20y&a=2")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the stub still serves after the flood");
        }
    }

    // ── Secrets in the provider's answer must not be copied into reports ──

    @Test
    void aSensitiveFieldsValueIsMaskedInMismatchMessages() throws Exception {
        Contract.Interaction login = interaction("{\"path\":\"/login\"}",
            "{\"status\":200,\"headers\":{\"Authorization\":\"Bearer expected-secret\"},\"body\":{\"access_token\":\"abc\",\"name\":\"ann\","
                + "\"password\":\"x\"},\"matchingRules\":{\"$.body.password\":{\"match\":\"integer\"}}}");
        List<String> mismatches = ContractVerifier.compare(login.response(), new ContractVerifier.ActualResponse(200,
            Map.of("authorization", "Bearer actual-secret"),
            "{\"access_token\":\"live-token-123\",\"name\":\"bob\",\"password\":\"hunter2\"}"));
        String all = String.join("\n", mismatches);
        assertFalse(all.contains("live-token-123") || all.contains("hunter2") || all.contains("actual-secret")
            || all.contains("expected-secret"), all);
        assertTrue(all.contains("expected \"ann\" but was \"bob\""), "ordinary fields stay readable: " + all);
        assertEquals(4, mismatches.size(), all); // Authorization, access_token, name, password
    }

    @Test
    void aHugeValueInAMismatchMessageIsCut() throws Exception {
        Contract.Interaction big = interaction("{\"path\":\"/x\"}", "{\"status\":200,\"body\":{\"v\":\"a\"}}");
        List<String> mismatches = ContractVerifier.compare(big.response(), new ContractVerifier.ActualResponse(200, Map.of(),
            "{\"v\":\"" + "z".repeat(50_000) + "\"}"));
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).length() < 300, "message length " + mismatches.get(0).length());
    }

    @Test
    void anOversizedContractFileIsRefusedBeforeItIsParsed() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("huge-contract", ".json");
        try {
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
                byte[] chunk = " ".repeat(1024 * 1024).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (int i = 0; i < 6; i++) {
                    out.write(chunk);
                }
            }
            try {
                Contract.load(file);
                throw new AssertionError("should have refused a 6 MB contract");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("larger than"), expected.getMessage());
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
