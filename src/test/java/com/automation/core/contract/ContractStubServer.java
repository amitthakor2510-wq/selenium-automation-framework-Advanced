package com.automation.core.contract;

import com.automation.core.contract.Contract.Interaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves a contract's interactions on a local port, so the CONSUMER's own tests run their client
 * against exactly the responses the contract promises. Together with {@link ContractVerifier}
 * (which checks the real provider against the same file) this is what makes the contract the
 * single source of truth for both sides.
 *
 * <pre>{@code
 * try (ContractStubServer stub = ContractStubServer.start(Contract.load(path))) {
 *     MyClient client = new MyClient(stub.baseUrl());
 *     assertEquals(client.getPost(1).getTitle(), "...");   // example values from the contract
 *     stub.assertAllInteractionsUsed();                    // the consumer really exercised the contract
 * }
 * }</pre>
 * A request that matches no interaction gets HTTP 500 and is listed by {@link #unmatchedRequests()}.
 * Matching is by method, path and the query parameters the contract lists; request headers and
 * bodies are not compared. Bodies are served as written in the contract (its example values).
 */
public final class ContractStubServer implements AutoCloseable {

    private static final int MAX_REQUEST_BODY_BYTES = 1_048_576;
    private static final int MAX_UNMATCHED_KEPT = 1000;

    private final HttpServer server;
    private final List<Interaction> interactions;
    private final Set<String> used = Collections.synchronizedSet(new LinkedHashSet<>());
    private final List<String> unmatched = Collections.synchronizedList(new ArrayList<>());

    private ContractStubServer(HttpServer server, List<Interaction> interactions) {
        this.server = server;
        this.interactions = interactions;
    }

    public static ContractStubServer start(Contract contract) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ContractStubServer stub = new ContractStubServer(server, contract.interactions());
        server.createContext("/", stub::handle);
        server.start();
        return stub;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Requests that matched nothing in the contract, as {@code "GET /path?query"}. */
    public List<String> unmatchedRequests() {
        return new ArrayList<>(unmatched);
    }

    /** Descriptions of interactions no request has hit yet. */
    public List<String> unusedInteractions() {
        List<String> unused = new ArrayList<>();
        for (Interaction interaction : interactions) {
            if (!used.contains(interaction.description())) {
                unused.add(interaction.description());
            }
        }
        return unused;
    }

    /** Fails when a request matched nothing, or part of the contract was never exercised. */
    public void assertAllInteractionsUsed() {
        List<String> problems = new ArrayList<>();
        if (!unmatched.isEmpty()) {
            problems.add("requests not in the contract: " + unmatched);
        }
        if (!unusedInteractions().isEmpty()) {
            problems.add("contract interactions never used: " + unusedInteractions());
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Interaction match = null;
        for (Interaction interaction : interactions) {
            if (interaction.request().method().equalsIgnoreCase(method)
                && interaction.request().path().equals(path)
                && queryMatches(interaction.request().query(), rawQuery)) {
                match = interaction;
                break;
            }
        }
        exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES); // the body is never matched: do not buffer a flood
        if (match == null) {
            if (unmatched.size() < MAX_UNMATCHED_KEPT) {
                unmatched.add(method + " " + path + (rawQuery == null ? "" : "?" + rawQuery));
            }
            ObjectNode error = new ObjectMapper().createObjectNode(); // Jackson escapes whatever the path contains
            error.put("error", "no interaction in the contract for " + method + " " + path);
            respond(exchange, 500, Map.of("Content-Type", "application/json"), error.toString());
            return;
        }
        used.add(match.description());
        Contract.Response response = match.response();
        Map<String, String> headers = new java.util.LinkedHashMap<>(response.headers());
        String body = "";
        if (response.body() != null && !response.body().isNull()) {
            body = response.body().isTextual() ? response.body().asText() : response.body().toString();
            if (headers.keySet().stream().noneMatch(h -> h.equalsIgnoreCase("content-type"))) {
                headers.put("Content-Type", response.body().isTextual() ? "text/plain" : "application/json");
            }
        }
        respond(exchange, response.status(), headers, body);
    }

    private static boolean queryMatches(Map<String, List<String>> expected, String rawQuery) {
        for (Map.Entry<String, List<String>> param : expected.entrySet()) {
            List<String> actual = new ArrayList<>();
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                    if (key.equals(param.getKey())) {
                        actual.add(decode(eq < 0 ? "" : pair.substring(eq + 1)));
                    }
                }
            }
            if (!actual.equals(param.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, String> headers, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
