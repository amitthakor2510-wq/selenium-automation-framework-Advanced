package com.automation.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A consumer-driven contract read from a Pact JSON file (specification v2 and v3 layouts): what a
 * consumer sends to a provider and the response it relies on. The same file drives both sides -
 * {@link ContractVerifier} replays it against the real provider, {@link ContractStubServer} serves
 * it to the consumer's own tests - so a provider change that breaks a consumer fails a test
 * instead of failing in production.
 *
 * <p>Supported: request method / path / query / headers / JSON body; response status / headers /
 * JSON or text body; matching rules {@code type}, {@code regex}, {@code integer}, {@code decimal},
 * {@code number}, {@code boolean}, {@code null}, {@code include}, {@code equality} and array
 * {@code min}/{@code max}, in both the v2 ({@code "$.body.id"}) and v3 ({@code "body": {"$.id"}})
 * notations. Not supported (a contract that uses them fails loudly rather than passing
 * silently): date/time matchers, message pacts, Pact v4 plugins, a broker.
 */
public record Contract(String consumer, String provider, List<Interaction> interactions, String source) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One request/response pair. {@code providerStates} are the setup names the provider should be put into first. */
    public record Interaction(String description, List<String> providerStates, Request request, Response response) {
    }

    public record Request(String method, String path, Map<String, List<String>> query,
                          Map<String, String> headers, JsonNode body) {
    }

    public record Response(int status, Map<String, String> headers, JsonNode body,
                           Map<String, List<Rule>> bodyRules) {
    }

    /** One matching rule: {@code match} is its kind ("type", "regex", ...); the rest are its optional settings. */
    public record Rule(String match, String regex, Integer min, Integer max, String value) {
    }

    private static final java.util.regex.Pattern METHOD = java.util.regex.Pattern.compile("[A-Z]{3,10}");
    private static final java.util.regex.Pattern HEADER_NAME = java.util.regex.Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final java.util.Set<String> FORBIDDEN_REQUEST_HEADERS =
        java.util.Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade");

    /**
     * Why this file cannot be trusted to run: no interactions at all (an empty file, or not a Pact
     * file - which would otherwise "verify" zero things and look green), or an interaction whose
     * request is unsafe to send. Empty means usable.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (interactions.isEmpty()) {
            problems.add("contains no interactions - is this a Pact file? It needs an \"interactions\" array");
        }
        for (Interaction interaction : interactions) {
            for (String problem : requestProblems(interaction.request())) {
                problems.add("interaction '" + interaction.description() + "': " + problem);
            }
            int status = interaction.response().status();
            if (status < 100 || status > 599) {
                problems.add("interaction '" + interaction.description() + "': response status " + status + " is not an HTTP status");
            }
        }
        return problems;
    }

    /**
     * What makes a request unsafe to replay against a real service. Contract files are reviewed
     * like code but can arrive in a pull request, and the verifier runs in CI with network access
     * and credentials, so a file must not be able to (a) send the request to a different host via an
     * absolute or protocol-relative "path", (b) smuggle a second request or header through CR/LF, or
     * (c) override framing headers. Only a relative path starting with a single '/' is accepted.
     */
    public static List<String> requestProblems(Request request) {
        List<String> problems = new ArrayList<>();
        String path = request.path();
        if (!path.startsWith("/") || path.startsWith("//")) {
            problems.add("the request path must be a relative path starting with a single '/', not '" + printable(path) + "'");
        }
        if (path.contains("://") || path.indexOf('\\') >= 0 || hasControlOrSpace(path)) {
            problems.add("the request path '" + printable(path) + "' contains a scheme, backslash, space or control character");
        }
        if (!METHOD.matcher(request.method()).matches()) {
            problems.add("the request method '" + printable(request.method()) + "' is not an HTTP method");
        }
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            String name = header.getKey();
            if (!HEADER_NAME.matcher(name).matches() || FORBIDDEN_REQUEST_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                problems.add("the request header name '" + printable(name) + "' is not allowed");
            }
            if (hasControl(header.getValue())) {
                problems.add("the value of request header '" + printable(name) + "' contains a control character");
            }
        }
        for (Map.Entry<String, List<String>> param : request.query().entrySet()) {
            if (hasControl(param.getKey()) || param.getValue().stream().anyMatch(Contract::hasControl)) {
                problems.add("the request query parameter '" + printable(param.getKey()) + "' contains a control character");
            }
        }
        return problems;
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f);
    }

    private static boolean hasControlOrSpace(String text) {
        return text.chars().anyMatch(c -> c <= 0x20 || c == 0x7f);
    }

    /** Safe to put in a message: control characters shown as '?', long text cut. */
    private static String printable(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}]", "?");
        return clean.length() > 80 ? clean.substring(0, 80) + "..." : clean;
    }

    /** A contract is a few KB; a multi-megabyte "contract" is a mistake or an attack on the CI runner's memory. */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    public static Contract load(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Contract file " + file.getFileName() + " is " + Files.size(file) / 1024
                + " KB - larger than the " + MAX_FILE_BYTES / 1024 + " KB limit");
        }
        return parse(MAPPER.readTree(file.toFile()), file.getFileName().toString());
    }

    /** All {@code *.json} files directly inside {@code dir} (empty if the folder is missing), in name order. */
    public static List<Contract> loadAll(Path dir) throws IOException {
        List<Contract> contracts = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return contracts;
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            contracts.add(load(file));
        }
        return contracts;
    }

    public static Contract parse(JsonNode root, String source) {
        List<Interaction> interactions = new ArrayList<>();
        for (JsonNode node : root.path("interactions")) {
            interactions.add(interaction(node));
        }
        return new Contract(root.path("consumer").path("name").asText("consumer"),
            root.path("provider").path("name").asText("provider"), interactions, source);
    }

    private static Interaction interaction(JsonNode node) {
        List<String> states = new ArrayList<>();
        if (node.hasNonNull("providerState")) {
            states.add(node.get("providerState").asText());
        }
        for (JsonNode state : node.path("providerStates")) {
            states.add(state.path("name").asText());
        }
        JsonNode req = node.path("request");
        JsonNode res = node.path("response");
        return new Interaction(node.path("description").asText("(no description)"), states,
            new Request(req.path("method").asText("GET").toUpperCase(java.util.Locale.ROOT),
                req.path("path").asText("/"), query(req.path("query")), strings(req.path("headers")), body(req)),
            new Response(res.path("status").asInt(200), strings(res.path("headers")), body(res), rules(res.path("matchingRules"))));
    }

    private static JsonNode body(JsonNode owner) {
        return owner.has("body") ? owner.get("body") : null;
    }

    /** v2 {@code "a=1&b=2"} or v3 {@code {"a": ["1"], "b": "2"}}. */
    private static Map<String, List<String>> query(JsonNode node) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (node.isTextual()) {
            for (String pair : node.asText().split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                // The v2 string is the raw query: decode it, so values are stored as plain text (like
                // the v3 object form) and are encoded exactly once, when a request is actually sent.
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = decode(eq < 0 ? "" : pair.substring(eq + 1));
                query.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<String> values = new ArrayList<>();
                if (field.getValue().isArray()) {
                    field.getValue().forEach(v -> values.add(v.asText()));
                } else {
                    values.add(field.getValue().asText());
                }
                query.put(field.getKey(), values);
            }
        }
        return query;
    }

    private static String decode(String raw) {
        return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            map.put(field.getKey(), field.getValue().asText());
        }
        return map;
    }

    /** Body rules only, from either notation, keyed by their path relative to the body ({@code $}, {@code $.id}, ...). */
    private static Map<String, List<Rule>> rules(JsonNode matchingRules) {
        Map<String, List<Rule>> rules = new LinkedHashMap<>();
        if (!matchingRules.isObject()) {
            return rules;
        }
        if (matchingRules.has("body")) {
            addRules(rules, matchingRules.get("body"), "");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = matchingRules.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equals("$.body") || field.getKey().startsWith("$.body.")
                || field.getKey().startsWith("$.body[")) {
                addRules(rules, MAPPER.createObjectNode().set(field.getKey(), field.getValue()), "$.body");
            }
        }
        return rules;
    }

    private static void addRules(Map<String, List<Rule>> rules, JsonNode byPath, String stripPrefix) {
        Iterator<Map.Entry<String, JsonNode>> fields = byPath.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = field.getKey();
            if (!stripPrefix.isEmpty() && path.startsWith(stripPrefix)) {
                path = "$" + path.substring(stripPrefix.length());
            }
            List<Rule> list = new ArrayList<>();
            JsonNode matchers = field.getValue().path("matchers");
            if (matchers.isArray()) {
                matchers.forEach(m -> list.add(rule(m)));
            } else {
                list.add(rule(field.getValue()));
            }
            rules.put(path, list);
        }
    }

    private static Rule rule(JsonNode node) {
        String match = node.path("match").asText("");
        if (match.isEmpty() && (node.has("min") || node.has("max"))) {
            match = "type"; // old notation: {"min": 1} alone means "an array of at least 1"
        }
        return new Rule(match, node.hasNonNull("regex") ? node.get("regex").asText() : null,
            node.has("min") ? node.get("min").asInt() : null, node.has("max") ? node.get("max").asInt() : null,
            node.hasNonNull("value") ? node.get("value").asText() : null);
    }
}
