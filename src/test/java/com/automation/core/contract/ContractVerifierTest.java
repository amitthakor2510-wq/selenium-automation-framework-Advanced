package com.automation.core.contract;

import com.automation.core.contract.ContractVerifier.ActualResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifier logic with a fake provider - no network. */
class ContractVerifierTest {

    private static Contract.Interaction interaction() throws IOException {
        return Contract.parse(new ObjectMapper().readTree(ContractTest.V2), "t.json").interactions().get(0);
    }

    private static ActualResponse ok(String body) {
        return new ActualResponse(200, Map.of("content-type", "application/json; charset=utf-8"), body);
    }

    @Test
    void aProviderThatHonoursTheContractPasses() throws Exception {
        ContractVerifier.Result result = ContractVerifier.verify(interaction(),
            request -> ok("[{\"id\":5,\"extra\":true},{\"id\":6}]"), null);
        assertTrue(result.passed(), result.mismatches().toString());
    }

    @Test
    void everyBreakingChangeIsReported() throws Exception {
        ContractVerifier.Result result = ContractVerifier.verify(interaction(),
            request -> new ActualResponse(500, Map.of("Content-Type", "text/html"), "[{\"id\":\"five\"}]"), null);
        assertFalse(result.passed());
        List<String> mismatches = result.mismatches();
        assertTrue(mismatches.contains("status: expected 200 but was 500"), mismatches.toString());
        assertTrue(mismatches.contains("header 'Content-Type': expected 'application/json' but was 'text/html'"), mismatches.toString());
        assertTrue(mismatches.contains("$[0].id: expected an integer but was \"five\""), mismatches.toString());
    }

    @Test
    void aMissingHeaderAndAMissingBodyAreReported() throws Exception {
        ContractVerifier.Result result = ContractVerifier.verify(interaction(),
            request -> new ActualResponse(200, Map.of(), ""), null);
        assertTrue(result.mismatches().contains("header 'Content-Type': missing from the response"), result.mismatches().toString());
        assertTrue(result.mismatches().contains("body: the response has no body"), result.mismatches().toString());
    }

    @Test
    void providerStatesAreSetUpBeforeTheRequestIsSent() throws Exception {
        List<String> events = new ArrayList<>();
        ContractVerifier.verify(interaction(), request -> {
            events.add("request");
            return ok("[{\"id\":1}]");
        }, state -> events.add("state:" + state));
        assertEquals(List.of("state:books exist", "request"), events);
    }

    @Test
    void aFailingExchangeOrStateIsAFailedResultNotAnException() throws Exception {
        ContractVerifier.Result down = ContractVerifier.verify(interaction(), request -> {
            throw new java.net.ConnectException("refused");
        }, null);
        assertFalse(down.passed());
        assertTrue(down.mismatches().get(0).contains("could not complete the request"), down.mismatches().toString());

        ContractVerifier.Result badState = ContractVerifier.verify(interaction(), request -> ok("[]"), state -> {
            throw new IllegalStateException("cannot create books");
        });
        assertFalse(badState.passed());
    }

    @Test
    void textBodiesAreComparedAsText() {
        Contract.Response expected = new Contract.Response(200, Map.of(), com.fasterxml.jackson.databind.node.TextNode.valueOf("pong"), Map.of());
        assertEquals(List.of(), ContractVerifier.compare(expected, new ActualResponse(200, Map.of(), "pong\n")));
        assertEquals(1, ContractVerifier.compare(expected, new ActualResponse(200, Map.of(), "ping")).size());
    }
}
